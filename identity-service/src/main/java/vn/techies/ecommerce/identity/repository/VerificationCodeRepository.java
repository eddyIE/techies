package vn.techies.ecommerce.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.identity.domain.VerificationCode;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;

import java.util.Optional;
import java.util.UUID;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findByUserIdAndPurpose(UUID userId, Purpose purpose);

    void deleteByUserIdAndPurpose(UUID userId, Purpose purpose);
}
