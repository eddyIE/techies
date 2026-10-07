package vn.techies.ecommerce.common.error;

/**
 * Stable, machine-readable error codes. The mobile client switches on these, so the constant
 * names are part of the API contract: rename one and you break a client.
 */
public enum ErrorCode {

    // 400
    VALIDATION_ERROR(400),
    UNSUPPORTED_IMAGE_TYPE(400),
    WEAK_PASSWORD(400),
    MALFORMED_REQUEST(400),

    // 401
    UNAUTHENTICATED(401),
    INVALID_TOKEN(401),
    INVALID_CREDENTIALS(401),

    // 403
    FORBIDDEN(403),

    // 404
    NOT_FOUND(404),
    ACCOUNT_NOT_FOUND(404),
    ADDRESS_NOT_FOUND(404),
    PRODUCT_NOT_FOUND(404),
    ORDER_NOT_FOUND(404),
    COUPON_NOT_FOUND(404),
    NOTHING_TO_RESTORE(404),
    GIFT_NOT_FOUND(404),
    VOUCHER_NOT_FOUND(404),

    // 409
    CONFLICT(409),
    EMAIL_ALREADY_EXISTS(409),
    INSUFFICIENT_STOCK(409),
    PRODUCT_UNAVAILABLE(409),
    EMPTY_CART(409),
    ORDER_NOT_CANCELLABLE(409),
    ORDER_NOT_PAYABLE(409),
    ORDER_NOT_REVIEWABLE(409),
    ALREADY_REVIEWED(409),
    COUPON_NOT_APPLICABLE(409),
    GIFT_ALREADY_CLAIMED(409),
    GIFT_OUT_OF_STOCK(409),
    INSUFFICIENT_POINTS(409),
    TIER_TOO_LOW(409),
    VOUCHER_ALREADY_CONSUMED(409),
    VOUCHER_EXPIRED(409),
    VOUCHER_NOT_OWNED(409),
    NOTHING_TO_RELEASE(409),

    // 413
    FILE_TOO_LARGE(413),

    // 422
    PAYMENT_FAILED(422),

    // 500 / 502
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(502);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
