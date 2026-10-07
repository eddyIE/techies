package vn.techies.ecommerce.loyalty.domain;

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
 * One row of the append-only ledger. Nothing here is ever updated or deleted, so both balances
 * can be summed from it rather than stored beside it.
 */
@Entity
@Table(name = "points_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointsEntry {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;

    @Column(nullable = false)
    private int points;

    @Column(nullable = false, length = 32)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private PointsEntry(UUID userId, EntryType entryType, int points, String reference) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.entryType = entryType;
        this.points = points;
        this.reference = reference;
        this.createdAt = Instant.now();
    }

    /** Credit for a completed order. {@code reference} is the order ref, and the idempotency key. */
    public static PointsEntry earn(UUID userId, int points, String orderRef) {
        return new PointsEntry(userId, EntryType.ORDER_EARN, points, orderRef);
    }

    /** Debit for a claimed gift, stored negative so the balance is a plain SUM. */
    public static PointsEntry spend(UUID userId, int points, UUID redemptionId) {
        return new PointsEntry(userId, EntryType.GIFT_SPEND, -points, redemptionId.toString());
    }
}
