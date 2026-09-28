package vn.techies.ecommerce.order.service.payment;

/**
 * What the app reports back after the customer has finished at the payment screen.
 *
 * <p>Separate from {@link PaymentSimulation}, which drives the mock processor's behaviour.
 * This is a result being reported, not an instruction about how to behave.
 */
public enum PaymentOutcome {

    /** The customer paid. The order is confirmed and its cart lines are cleared. */
    SUCCESS,

    /** Declined, cancelled or abandoned at the payment screen. The stock is released. */
    FAILED
}
