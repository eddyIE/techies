package vn.techies.ecommerce.order.service;

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
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.domain.ShippingAddress;
import vn.techies.ecommerce.order.repository.OrderRefSequence;
import vn.techies.ecommerce.order.service.payment.PaymentResult;
import vn.techies.ecommerce.order.service.payment.PaymentSimulator;

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
 * <p>Step 5 (deduct stock) is the step that must be compensated. If payment then fails, step 6
 * calls inventory's restore to put the stock back, and the order is marked FAILED. That
 * compensating transaction is the reason this project is microservices.
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
    private final PaymentSimulator paymentSimulator;
    private final IdentityClient identityClient;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;

    public Order checkout(UUID userId, CheckoutRequest request) {
        // ---- Step 1: cart ----------------------------------------------------------------
        List<CartLine> lines = loadCartLines(userId);

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
            // No payment is attempted, and there is nothing to compensate: stock never moved.
            return failOrder(order, code);
        }

        // ---- Step 6: payment, with compensation on failure -------------------------------
        record(order, "6-CHARGE_PAYMENT", SagaStepStatus.STARTED, request.paymentMethod().name());
        PaymentResult payment;
        try {
            payment = paymentSimulator.charge(order.getOrderRef(), order.getTotal(),
                    request.paymentMethod(), request.simulatePayment());
        } catch (Exception ex) {
            record(order, "6-CHARGE_PAYMENT", SagaStepStatus.FAILED, ex.toString());
            compensate(order, stockLines);
            return failOrder(order, FailureCode.PAYMENT_FAILED);
        }

        if (!payment.approved()) {
            record(order, "6-CHARGE_PAYMENT", SagaStepStatus.FAILED, payment.reason());
            compensate(order, stockLines);
            return failOrder(order, FailureCode.PAYMENT_FAILED);
        }
        record(order, "6-CHARGE_PAYMENT", SagaStepStatus.SUCCESS, payment.reason());

        // ---- Step 7: confirm and clear the cart ------------------------------------------
        Order confirmed = confirmOrder(order.getId());
        cartService.clear(userId);
        record(confirmed, "7-CONFIRM_ORDER", SagaStepStatus.SUCCESS, "cart cleared");

        log.info("Order {} confirmed for user {}", confirmed.getOrderRef(), userId);
        return confirmed;
    }

    /**
     * The compensating transaction. Best-effort but always recorded: if restore itself fails
     * the order is still marked FAILED and the stranded reference is written to saga_steps, so
     * it can be replayed by hand. restore is idempotent, so replay is safe.
     */
    private void compensate(Order order, List<InventoryClient.StockLine> stockLines) {
        try {
            inventoryClient.restore(
                    new InventoryClient.StockMovementRequest(order.getOrderRef(), stockLines));
            record(order, "5-DEDUCT_STOCK", SagaStepStatus.COMPENSATED, "stock restored");
            log.info("Compensated order {}: stock restored", order.getOrderRef());
        } catch (Exception ex) {
            record(order, "5-DEDUCT_STOCK", SagaStepStatus.FAILED,
                    "COMPENSATION FAILED, stock stranded for " + order.getOrderRef() + ": " + ex);
            log.error("Compensation failed for order {}; stock is stranded and needs a manual "
                    + "restore replay", order.getOrderRef(), ex);
        }
    }

    // ---- individual steps ----------------------------------------------------------------

    private List<CartLine> loadCartLines(UUID userId) {
        List<CartService.CartLineSnapshot> snapshot = cartService.lineSnapshot(userId);
        if (snapshot.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_CART, "Your cart is empty");
        }
        List<CartLine> lines = new ArrayList<>();
        for (CartService.CartLineSnapshot line : snapshot) {
            lines.add(new CartLine(line.productId(), line.quantity()));
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

        Order order = Order.pending(orderRefSequence.next(), userId, address,
                request.paymentMethod(), subtotal, shippingPolicy.feeFor(subtotal));

        for (CartLine line : lines) {
            CatalogClient.ProductSnapshot product = products.get(line.productId());
            order.addItem(line.productId(), product.name(), product.price(), line.quantity());
        }
        return orderWriter.save(order);
    }

    private Order failOrder(Order order, FailureCode code) {
        return orderWriter.fail(order.getId(), code);
    }

    private Order confirmOrder(UUID orderId) {
        return orderWriter.confirm(orderId);
    }

    private void record(Order order, String step, SagaStepStatus status, String detail) {
        sagaRecorder.record(order.getId(), step, status, detail);
    }

    /** Distinguishes "not enough stock" from "inventory is down" — different failure codes. */
    private static boolean isInsufficientStock(Exception ex) {
        String message = ex.toString();
        return message.contains("INSUFFICIENT_STOCK") || message.contains("409");
    }

    private record CartLine(UUID productId, int quantity) {
    }
}
