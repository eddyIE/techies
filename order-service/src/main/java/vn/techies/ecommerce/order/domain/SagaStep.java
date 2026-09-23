package vn.techies.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only trace of the checkout saga. Every step writes one row, so the distributed
 * transaction — including its compensation — can be shown rather than just asserted.
 */
@Entity
@Table(name = "saga_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaStep {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "step_name", nullable = false, length = 48)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SagaStepStatus status;

    @Column
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static SagaStep of(UUID orderId, String stepName, SagaStepStatus status, String detail) {
        SagaStep step = new SagaStep();
        step.id = UUID.randomUUID();
        step.orderId = orderId;
        step.stepName = stepName;
        step.status = status;
        step.detail = detail;
        step.createdAt = Instant.now();
        return step;
    }
}
