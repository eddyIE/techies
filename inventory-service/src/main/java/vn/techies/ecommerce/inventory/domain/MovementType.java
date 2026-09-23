package vn.techies.ecommerce.inventory.domain;

public enum MovementType {
    /** Stock leaves the shelf at checkout. */
    DEDUCT,
    /** The compensating transaction: payment failed, or the order was cancelled. */
    RESTORE
}
