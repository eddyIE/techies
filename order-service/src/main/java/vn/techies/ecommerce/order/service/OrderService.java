package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderItemResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderSummary;
import vn.techies.ecommerce.order.api.dto.OrderDtos.PageResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ShippingAddressResponse;
import vn.techies.ecommerce.order.client.InventoryClient;
import vn.techies.ecommerce.order.domain.FailureCode;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderItem;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.domain.ShippingAddress;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderLinePreview;
import vn.techies.ecommerce.order.repository.OrderRepository;
import vn.techies.ecommerce.order.repository.ProductReviewRepository;
import vn.techies.ecommerce.order.service.payment.PaymentSimulator;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final InventoryClient inventoryClient;
    private final PaymentSimulator paymentSimulator;
    private final ProductReviewRepository reviews;

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(UUID userId, OrderStatus status, int page, int size) {
        int effectiveSize = Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE);
        Page<Order> found = orders.findForUser(userId, status,
                PageRequest.of(Math.max(page, 0), effectiveSize));

        // One query for the whole page. Asking per order would be an N+1 on the list screen,
        // which is the most-visited screen in the app.
        List<UUID> itemIds = found.getContent().stream()
                .flatMap(o -> o.getItems().stream().map(OrderItem::getId))
                .toList();
        Set<UUID> reviewed = itemIds.isEmpty() ? Set.of()
                : Set.copyOf(reviews.reviewedItemIdsIn(itemIds));

        List<OrderSummary> content = found.getContent().stream()
                .map(o -> new OrderSummary(o.getId(), o.getOrderRef(), o.getStatus(),
                        o.getFailureCode(), o.getTotal(), o.getItems().size(),
                        firstLine(o), isFullyReviewed(o, reviewed), o.getCreatedAt()))
                .toList();

        return new PageResponse<>(content, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderResponse detail(UUID orderId, UUID userId) {
        Order order = loadOwned(orderId, userId);
        List<UUID> itemIds = order.getItems().stream().map(OrderItem::getId).toList();
        Set<UUID> reviewed = itemIds.isEmpty() ? Set.of()
                : Set.copyOf(reviews.reviewedItemIdsIn(itemIds));
        return toResponse(order, reviewed);
    }

    /** The line an order row shows: a picture and a name, without fetching every order. */
    private static OrderLinePreview firstLine(Order order) {
        return order.getItems().stream().findFirst()
                .map(i -> new OrderLinePreview(i.getProductId(), i.getProductName(),
                        i.getThumbnailUrl(), i.getQuantity()))
                .orElse(null);
    }

    /**
     * Whether nothing is left to review. Only a settled order can be, so an unpaid or failed
     * one reports false rather than "nothing outstanding", which would read as done.
     */
    private static boolean isFullyReviewed(Order order, Set<UUID> reviewedItemIds) {
        if (order.getStatus() != OrderStatus.CONFIRMED && order.getStatus() != OrderStatus.COMPLETED) {
            return false;
        }
        return !order.getItems().isEmpty() && order.getItems().stream()
                .allMatch(i -> reviewedItemIds.contains(i.getId()));
    }

    /**
     * Moves an order to a chosen status, for demonstrating the lifecycle.
     *
     * <p>Nothing else moves an order on from CONFIRMED — there is no management app and so no
     * actor to do it, which is why COMPLETED was unreachable. This stands in for that actor.
     *
     * <p>It rewrites the order only. Stock is <strong>not</strong> adjusted, so sending
     * CANCELLED here leaves the stock deducted; {@code POST /orders/{id}/cancel} is the real
     * cancellation that returns it. Payment status follows the status, because an order shown
     * as paid with an unpaid payment status reads as a bug on the app's screens.
     */
    @Transactional
    public OrderResponse updateStatus(UUID orderId, UUID userId, OrderStatus target) {
        Order order = loadOwned(orderId, userId);

        switch (target) {
            case PENDING -> throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "PENDING is an internal state and cannot be set");
            case AWAITING_PAYMENT -> order.awaitPayment();
            case CONFIRMED -> order.confirm(null);
            case COMPLETED -> order.complete();
            case FAILED -> order.fail(FailureCode.PAYMENT_FAILED);
            case CANCELLED -> order.cancel();
        }

        log.info("Order {} moved to {} by user {} via the demo endpoint",
                order.getOrderRef(), target, userId);
        return detail(orderId, userId);
    }

    /**
     * Cancels a confirmed order and returns its stock.
     *
     * <p>Only CONFIRMED orders can be cancelled. A FAILED order's stock was already restored
     * by the saga, so restoring it again would invent inventory — inventory-service would
     * reject the second restore anyway, but failing fast here gives a clearer error.
     */
    @Transactional
    public OrderResponse cancel(UUID orderId, UUID userId) {
        Order order = loadOwned(orderId, userId);

        if (!order.isCancellable()) {
            throw new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "An order in status " + order.getStatus() + " cannot be cancelled");
        }

        List<InventoryClient.StockLine> lines = order.getItems().stream()
                .map(i -> new InventoryClient.StockLine(i.getProductId(), i.getQuantity()))
                .toList();

        try {
            inventoryClient.restore(
                    new InventoryClient.StockMovementRequest(order.getOrderRef(), lines));
        } catch (Exception ex) {
            // Refuse to cancel rather than cancel without returning stock: a silent mismatch
            // between order state and inventory is worse than a failed cancellation.
            log.error("Could not restore stock while cancelling {}", order.getOrderRef(), ex);
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Could not cancel right now, please try again");
        }

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            paymentSimulator.refund(order.getOrderRef(), order.getTotal());
        }
        order.cancel();

        log.info("Order {} cancelled by user {}", order.getOrderRef(), userId);
        // A cancelled order is not reviewable, so nothing is outstanding to report.
        return toResponse(order, Set.of());
    }

    private Order loadOwned(UUID orderId, UUID userId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw ApiException.forbidden("you may only view your own orders");
        }
        return order;
    }

    /** @param reviewedItemIds the lines already reviewed; pass an empty set when unknown. */
    public static OrderResponse toResponse(Order order, Set<UUID> reviewedItemIds) {
        ShippingAddress a = order.getShippingAddress();
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> toItemResponse(i, reviewedItemIds))
                .toList();

        return new OrderResponse(order.getId(), order.getOrderRef(), order.getStatus(),
                order.getFailureCode(), order.getSubtotal(), order.getShippingFee(),
                order.getTotal(), order.getCouponCode(), order.getDiscount(),
                order.getPaymentMethod(), order.getPaymentStatus(),
                order.getPaymentRef(),
                new ShippingAddressResponse(a.getRecipientName(), a.getPhone(), a.getLine1(),
                        a.getWard(), a.getDistrict(), a.getProvince()),
                items, order.getCreatedAt());
    }

    private static OrderItemResponse toItemResponse(OrderItem item, Set<UUID> reviewedItemIds) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal(),
                item.getThumbnailUrl(), reviewedItemIds.contains(item.getId()));
    }
}
