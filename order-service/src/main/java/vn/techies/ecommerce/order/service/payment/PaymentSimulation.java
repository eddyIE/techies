package vn.techies.ecommerce.order.service.payment;

/** Demo control for the mock card processor. Never reaches a real provider. */
public enum PaymentSimulation {
    SUCCESS,
    DECLINED,
    /** Exceeds the step budget and is treated as a decline, exercising the same compensation. */
    TIMEOUT
}
