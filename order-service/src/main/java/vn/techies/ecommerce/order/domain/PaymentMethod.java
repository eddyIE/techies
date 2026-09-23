package vn.techies.ecommerce.order.domain;

public enum PaymentMethod {
    /** Cash on delivery: always approves immediately, nothing to charge. */
    COD,
    /** Simulated card. Outcome is driven by the checkout request, for demo purposes. */
    MOCK_CARD
}
