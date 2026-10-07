package vn.techies.ecommerce.order.domain;

/**
 * Which system granted this order's discount. Recorded because only one of them has to be
 * compensated: a coupon is reusable and needs nothing undone, while a loyalty voucher is
 * single-use and has to be handed back.
 */
public enum DiscountSource {
    NONE,
    COUPON,
    LOYALTY_VOUCHER
}
