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
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderItem;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.PaymentStatus;
import vn.techies.ecommerce.order.domain.ShippingAddress;
import vn.techies.ecommerce.order.repository.OrderRepository;
import vn.techies.ecommerce.order.service.payment.PaymentSimulator;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final InventoryClient inventoryClient;
    private final PaymentSimulator paymentSimulator;

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(UUID userId, OrderStatus status, int page, int size) {
        int effectiveSize = Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE);
        Page<Order> found = orders.findForUser(userId, status,
                PageRequest.of(Math.max(page, 0), effectiveSize));

        List<OrderSummary> content = found.getContent().stream()
                .map(o -> new OrderSummary(o.getId(), o.getOrderRef(), o.getStatus(),
                        o.getFailureCode(), o.getTotal(), o.getItems().size(), o.getCreatedAt()))
                .toList();

        return new PageResponse<>(content, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderResponse detail(UUID orderId, UUID userId) {
        return toResponse(loadOwned(orderId, userId));
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
        return toResponse(order);
    }

    private Order loadOwned(UUID orderId, UUID userId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw ApiException.forbidden("you may only view your own orders");
        }
        return order;
    }

    public static OrderResponse toResponse(Order order) {
        ShippingAddress a = order.getShippingAddress();
        List<OrderItemResponse> items = order.getItems().stream()
                .map(OrderService::toItemResponse)
                .toList();

        return new OrderResponse(order.getId(), order.getOrderRef(), order.getStatus(),
                order.getFailureCode(), order.getSubtotal(), order.getShippingFee(),
                order.getTotal(), order.getPaymentMethod(), order.getPaymentStatus(),
                new ShippingAddressResponse(a.getRecipientName(), a.getPhone(), a.getLine1(),
                        a.getWard(), a.getDistrict(), a.getProvince()),
                items, order.getCreatedAt());
    }

    private static OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal());
    }
}
