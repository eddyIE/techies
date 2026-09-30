package vn.techies.ecommerce.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One row of a product's specification table, as the customer reads it.
 *
 * <p>Stored as rows rather than a JSON blob for the same reason as {@link ProductImage}:
 * the order is part of the data, and a flat table keeps a future "filter by screen size"
 * an ordinary query rather than a rewrite.
 */
@Entity
@Table(name = "product_specs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSpec {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
