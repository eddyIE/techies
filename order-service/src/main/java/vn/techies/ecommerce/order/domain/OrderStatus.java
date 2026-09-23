package vn.techies.ecommerce.order.domain;

/**
 * There is no SHIPPED or DELIVERED: with no admin site nothing could ever drive those
 * transitions, so they would be unreachable states (docs/SPEC-order.md).
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    CANCELLED
}
