package vn.techies.ecommerce.order.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.OrderDtos.CheckoutRequest;
import vn.techies.ecommerce.order.client.CatalogClient;
import vn.techies.ecommerce.order.client.IdentityClient;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.client.LoyaltyClient;
import vn.techies.ecommerce.order.domain.Coupon;
import vn.techies.ecommerce.order.domain.DiscountSource;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.PaymentMethod;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.domain.ShippingAddress;
import vn.techies.ecommerce.order.repository.CouponRepository;
import vn.techies.ecommerce.order.repository.OrderRefSequence;
import vn.techies.ecommerce.order.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates checkout across identity, catalog and inventory.
 *
 * <p>The saga has seven steps. Steps 1-3 run before any order row exists, so a trivially
 * invalid checkout does not litter order history. From step 4 onward every outcome is a
 * persisted order the app can display — including the failures.
 *
 * <p>Step 5 (deduct stock) is the step that must be compensated. That compensating
 * transaction is the reason this project is microservices.
 *
 * <p>The saga no longer finishes in one request for a method that has to be settled. Stock is
 * taken, the order is left AWAITING_PAYMENT, and the customer pays in the app;
 * {@code PaymentService} then confirms the order or runs the same compensation a failed
 * charge used to. {@code PendingPaymentSweeper} compensates the orders nobody ever comes back
 * to. COD still completes inline, because there is nothing to collect before delivery.
 *
 * <p>Deliberately NOT a single @Transactional method: each step commits so that the saga_steps
 * trail survives a failure. A local transaction spanning remote calls would roll the trail
 * back along with everything else, and could not undo the remote effects anyway.
 */
@Service
@RequiredArgsConstructor
public class CheckoutSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaOrchestrator.class);

    private final CartService cartService;
    private final OrderWriter orderWriter;
    private final SagaRecorder sagaRecorder;
    private final OrderRefSequence orderRefSequence;
    private final ShippingPolicy shippingPolicy;
    private final CouponRepository coupons;
    private final OrderRepository orders;
    private final PaymentService paymentService;
    private final IdentityClient identityClient;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final LoyaltyClient loyaltyClient;
    private final VoucherCompensator voucherCompensator;

    public Order checkout(UUID userId, CheckoutRequest request) {
        // ---- Step 1: cart ----------------------------------------------------------------
        // Either the whole cart, or just the lines the customer ticked on the cart screen.
        List<CartLine> lines = loadCartLines(userId, request.cartItemIds());

        // ---- Step 2: address snapshot ----------------------------------------------------
        ShippingAddress address = snapshotAddress(request.addressId(), userId);

        // ---- Step 3: price snapshot ------------------------------------------------------
        Map<UUID, CatalogClient.ProductSnapshot> products = snapshotProducts(lines);

        // ---- Step 4: persist the order ---------------------------------------------------
        Order order = persistPendingOrder(userId, request, lines, address, products);
        record(order, "1-LOAD_CART", SagaStepStatus.SUCCESS, lines.size() + " line(s)");
        record(order, "2-SNAPSHOT_ADDRESS", SagaStepStatus.SUCCESS, address.getProvince());
        record(order, "3-SNAPSHOT_PRODUCTS", SagaStepStatus.SUCCESS, products.size() + " product(s)");
        record(order, "4-PERSIST_ORDER", SagaStepStatus.SUCCESS, order.getOrderRef());

        // ---- Step 4b: release whatever the customer abandoned --------------------------
        // They opened the payment screen, went back and checked out again. That earlier order
        // is still holding its stock, so taking it again here would hold the same goods
        // twice and can report the customer's own basket as out of stock.
        releaseAbandonedOrders(userId, order.getOrderRef());

        // ---- Step 5: deduct stock (the compensatable step) -------------------------------
        List<InventoryClient.StockLine> stockLines = lines.stream()
                .map(l -> new InventoryClient.StockLine(l.productId(), l.quantity()))
                .toList();
        record(order, "5-DEDUCT_STOCK", SagaStepStatus.STARTED, null);
        try {
            inventoryClient.deduct(new InventoryClient.StockMovementRequest(order.getOrderRef(), stockLines));
            record(order, "5-DEDUCT_STOCK", SagaStepStatus.SUCCESS, null);
        } catch (Exception ex) {
            FailureCode code = isInsufficientStock(ex)
                    ? FailureCode.OUT_OF_STOCK : FailureCode.SERVICE_UNAVAILABLE;
            record(order, "5-DEDUCT_STOCK", SagaStepStatus.FAILED, ex.toString());
            // Stock never moved, so there is none to put back, but the voucher was consumed
            // before the order row and has to be returned.
            voucherCompensator.releaseIfHeld(order);
            return failOrder(order, code);
        }

        // ---- Step 6: the goods are now committed to this order ---------------------------
        // Remove only what was ordered. A partial checkout must leave the unselected lines in
        // the cart, and clearing everything would silently discard them. If the payment then
        // fails, PaymentService puts these lines back.
        cartService.removeItems(userId, lines.stream().map(CartLine::itemId).toList());

        // COD settles at the door, so there is nothing to collect and the order is done here.
        if (request.paymentMethod() == PaymentMethod.COD) {
            record(order, "6-CHARGE_PAYMENT", SagaStepStatus.SUCCESS, "COD, collected on delivery");
            Order confirmed = confirmOrder(order.getId(), null);
            record(confirmed, "7-CONFIRM_ORDER", SagaStepStatus.SUCCESS, "cart cleared");

            log.info("Order {} confirmed for user {} (COD)", confirmed.getOrderRef(), userId);
            return confirmed;
        }

        // Anything that has to be settled stops here, holding stock, while the customer pays
        // in the app. POST /orders/{id}/payment finishes the saga either way: it confirms the
        // order, or it releases the stock and returns these lines to the cart.
        record(order, "6-CHARGE_PAYMENT", SagaStepStatus.STARTED, "awaiting customer payment");
        Order awaiting = orderWriter.awaitPayment(order.getId());
        log.info("Order {} awaiting payment for user {}", awaiting.getOrderRef(), userId);
        return awaiting;
    }

    // ---- individual steps ----------------------------------------------------------------

    private List<CartLine> loadCartLines(UUID userId, List<UUID> selectedItemIds) {
        List<CartService.CartLineSnapshot> snapshot = cartService.lineSnapshot(userId, selectedItemIds);
        if (snapshot.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_CART, "Your cart is empty");
        }
        List<CartLine> lines = new ArrayList<>();
        for (CartService.CartLineSnapshot line : snapshot) {
            lines.add(new CartLine(line.itemId(), line.productId(), line.quantity()));
        }
        return lines;
    }

    private ShippingAddress snapshotAddress(UUID addressId, UUID userId) {
        try {
            IdentityClient.AddressSnapshot a = identityClient.getAddress(addressId, userId);
            return new ShippingAddress(a.recipientName(), a.phone(), a.line1(),
                    a.ward(), a.district(), a.province());
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            // A 404 from identity means the address is not the caller's, or does not exist.
            throw new ApiException(ErrorCode.ADDRESS_NOT_FOUND,
                    "Delivery address not found for this account");
        }
    }

    private Map<UUID, CatalogClient.ProductSnapshot> snapshotProducts(List<CartLine> lines) {
        List<UUID> ids = lines.stream().map(CartLine::productId).toList();
        List<CatalogClient.ProductSnapshot> snapshots;
        try {
            snapshots = catalogClient.batch(new CatalogClient.BatchRequest(ids));
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Product catalog is unavailable");
        }

        Map<UUID, CatalogClient.ProductSnapshot> byId = new HashMap<>();
        snapshots.forEach(s -> byId.put(s.id(), s));

        for (CartLine line : lines) {
            CatalogClient.ProductSnapshot product = byId.get(line.productId());
            if (product == null) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product " + line.productId() + " no longer exists");
            }
            if (!product.active()) {
                throw new ApiException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "'" + product.name() + "' is no longer available");
            }
        }
        return byId;
    }

    private Order persistPendingOrder(UUID userId, CheckoutRequest request, List<CartLine> lines,
                                      ShippingAddress address,
                                      Map<UUID, CatalogClient.ProductSnapshot> products) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartLine line : lines) {
            BigDecimal price = products.get(line.productId()).price();
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(line.quantity())));
        }

        // Drawn before the discount is resolved: consuming a voucher has to cite the order it
        // is held for, and that has to be the ref this order will carry.
        String orderRef = orderRefSequence.next();
        AppliedDiscount applied = resolveDiscount(userId, orderRef, request.couponCode(), subtotal);

        Order order = Order.pending(orderRef, userId, address,
                request.paymentMethod(), subtotal, shippingPolicy.feeFor(subtotal),
                applied.code(), applied.amount(), applied.source());

        for (CartLine line : lines) {
            CatalogClient.ProductSnapshot product = products.get(line.productId());
            order.addItem(line.productId(), product.name(), product.price(), line.quantity(),
                    product.thumbnailUrl());
        }
        return orderWriter.save(order);
    }

    /** Fails this customer's unpaid orders so their stock is available to the new one. */
    private void releaseAbandonedOrders(UUID userId, String replacedBy) {
        for (Order stale : orders.findAwaitingPaymentFor(userId)) {
            paymentService.releaseSuperseded(stale.getId(), "abandoned, replaced by " + replacedBy);
        }
    }

    /**
     * Resolves the submitted code to a discount: a coupon first, then a loyalty voucher.
     *
     * <p>Coupons are checked locally and win ties, because they are this service's own data and
     * a lookup beats a network call. Only an unknown code falls through to loyalty, so an
     * ordinary coupon checkout never depends on loyalty being up.
     *
     * <p>Rejected before any order row exists, like the other input problems: an unusable code
     * is a mistake to correct on the checkout screen, not a failed order in someone's history.
     * That is also why the voucher is consumed here rather than after the order is written —
     * a voucher another cart is holding must not produce a FAILED order.
     */
    private AppliedDiscount resolveDiscount(UUID userId, String orderRef, String code,
                                            BigDecimal subtotal) {
        if (code == null || code.isBlank()) {
            return AppliedDiscount.none();
        }
        String normalised = code.strip().toUpperCase();

        return coupons.findById(normalised)
                .map(coupon -> fromCoupon(coupon, normalised, subtotal))
                .orElseGet(() -> fromLoyaltyVoucher(userId, orderRef, normalised, subtotal));
    }

    private AppliedDiscount fromCoupon(Coupon coupon, String normalised, BigDecimal subtotal) {
        if (!coupon.isUsable()) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon '" + normalised + "' is no longer available");
        }
        if (!coupon.appliesTo(subtotal)) {
            throw new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon '" + normalised + "' needs an order of at least "
                            + coupon.getMinOrderTotal().toBigInteger() + " VND");
        }
        return new AppliedDiscount(coupon.getCode(), coupon.discountFor(subtotal),
                DiscountSource.COUPON);
    }

    /**
     * Spends a tier voucher on this order.
     *
     * <p>Loyalty's refusals are translated into the coupon codes the app already handles, so
     * the checkout screen needs no second vocabulary for a code it cannot tell apart anyway:
     * 404 means no such code in either system, and 409 means it exists but cannot be used.
     */
    private AppliedDiscount fromLoyaltyVoucher(UUID userId, String orderRef, String code,
                                               BigDecimal subtotal) {
        try {
            LoyaltyClient.ConsumeResponse consumed = loyaltyClient.consume(code,
                    new LoyaltyClient.ConsumeRequest(userId, orderRef, subtotal));
            log.info("Order {} consumed loyalty voucher {} for {}", orderRef, code,
                    consumed.discount());
            return new AppliedDiscount(code, consumed.discount(), DiscountSource.LOYALTY_VOUCHER);
        } catch (FeignException ex) {
            throw switch (ex.status()) {
                case 404 -> new ApiException(ErrorCode.COUPON_NOT_FOUND,
                        "Coupon '" + code + "' does not exist");
                case 409 -> new ApiException(ErrorCode.COUPON_NOT_APPLICABLE,
                        "Voucher '" + code + "' is no longer available");
                default -> new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Could not check that code right now, please try again");
            };
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Could not check that code right now, please try again");
        }
    }

    /** What the submitted code resolved to, and which system has to be compensated for it. */
    private record AppliedDiscount(String code, BigDecimal amount, DiscountSource source) {

        static AppliedDiscount none() {
            return new AppliedDiscount(null, BigDecimal.ZERO, DiscountSource.NONE);
        }
    }

    private Order failOrder(Order order, FailureCode code) {
        return orderWriter.fail(order.getId(), code);
    }

    private Order confirmOrder(UUID orderId, String paymentRef) {
        return orderWriter.confirm(orderId, paymentRef);
    }

    private void record(Order order, String step, SagaStepStatus status, String detail) {
        sagaRecorder.record(order.getId(), step, status, detail);
    }

    /** Distinguishes "not enough stock" from "inventory is down" — different failure codes. */
    private static boolean isInsufficientStock(Exception ex) {
        String message = ex.toString();
        return message.contains("INSUFFICIENT_STOCK") || message.contains("409");
    }

    private record CartLine(UUID itemId, UUID productId, int quantity) {
    }
}
