package vn.techies.ecommerce.loyalty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A reward in the exchange catalogue, collected in store with a claim code. */
@Entity
@Table(name = "gifts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Gift {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "points_cost", nullable = false)
    private int pointsCost;

    /** 0 means anyone can claim it. Higher tiers unlock the better rewards. */
    @Column(name = "min_tier", nullable = false)
    private short minTier;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active;

    public boolean isInStock() {
        return stock > 0;
    }
}
