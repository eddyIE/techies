package vn.techies.ecommerce.order.domain;

public enum SagaStepStatus {
    STARTED,
    SUCCESS,
    FAILED,
    /** The step's effect was undone by a compensating transaction. */
    COMPENSATED
}
