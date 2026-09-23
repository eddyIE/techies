package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.order.domain.SagaStep;
import vn.techies.ecommerce.order.domain.SagaStepStatus;
import vn.techies.ecommerce.order.repository.SagaStepRepository;

import java.util.List;
import java.util.UUID;

/**
 * Writes the saga trail.
 *
 * <p>A separate bean on purpose: REQUIRES_NEW is applied by a Spring proxy, and a proxy is
 * bypassed when a method is called through {@code this} from within the same class. Keeping
 * this out of the orchestrator is what makes each step's commit real rather than decorative.
 */
@Component
@RequiredArgsConstructor
public class SagaRecorder {

    private final SagaStepRepository sagaSteps;

    /**
     * Each step commits independently, so the trail survives a later failure. If this shared
     * a transaction with the checkout, a rollback would erase the very evidence of what went
     * wrong — and could not undo the remote calls anyway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orderId, String step, SagaStepStatus status, String detail) {
        sagaSteps.save(SagaStep.of(orderId, step, status, detail));
    }

    @Transactional(readOnly = true)
    public List<SagaStep> trailFor(UUID orderId) {
        return sagaSteps.findByOrderIdOrderByCreatedAtAsc(orderId);
    }
}
