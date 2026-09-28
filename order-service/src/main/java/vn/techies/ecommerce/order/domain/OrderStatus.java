package vn.techies.ecommerce.order.domain;

/**
 * The states an order can hold.
 *
 * <p>{@link #COMPLETED} is accepted by the API and the database but **nothing sets it**. With
 * no management app there is no actor to move an order on from {@link #CONFIRMED}, so the
 * transition would have no trigger. It is kept so the lifecycle can be demonstrated, so
 * filtering by it behaves, and so a row written directly — or by a future admin tool — is not
 * rejected by the CHECK constraint.
 *
 * <p>There is still no SHIPPED or DELIVERED: those would need a fulfilment process that does
 * not exist here, and unreachable states that look like features are worse than absent ones.
 */
public enum OrderStatus {

    /**
     * Created, before stock and payment have been resolved. Never returned to a client.
     *
     * <p>The saga persists the order in this state so a failure still leaves a row to explain
     * itself, then moves it on within the same request. Nothing rests here.
     */
    PENDING,

    /**
     * Stock is held and the order is waiting for the customer to pay.
     *
     * <p>This is the state the app sees and acts on: checkout returns it for any method that
     * has to be settled, the app takes the customer to payment, and
     * {@code POST /orders/{id}/payment} resolves it. COD never rests here, because there is
     * nothing to settle before delivery.
     *
     * <p>An order left here is holding stock nobody has paid for, so
     * {@code PendingPaymentSweeper} expires it and puts the stock back.
     */
    AWAITING_PAYMENT,

    /** Paid and stock deducted. The only status from which an order can be cancelled. */
    CONFIRMED,

    /** Fulfilled. Terminal, and not reachable through the API today — see above. */
    COMPLETED,

    /** Stock or payment failed. Terminal; any stock taken was already restored. */
    FAILED,

    /** Cancelled by the customer. Terminal; stock was returned and payment refunded. */
    CANCELLED
}
