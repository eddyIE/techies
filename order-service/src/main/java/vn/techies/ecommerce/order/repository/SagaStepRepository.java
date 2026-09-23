package vn.techies.ecommerce.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.order.domain.SagaStep;

import java.util.List;
import java.util.UUID;

public interface SagaStepRepository extends JpaRepository<SagaStep, UUID> {

    List<SagaStep> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
