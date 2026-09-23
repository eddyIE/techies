package vn.techies.ecommerce.catalog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.techies.ecommerce.catalog.domain.Product;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByIdAndActiveTrue(UUID id);

    /**
     * Browse and search in one query. Every filter is optional: a null parameter disables
     * its clause, so the mobile app can combine keyword, category and price freely.
     *
     * <p>Native because it uses immutable_unaccent + the trigram index for accent-insensitive
     * matching, which JPQL cannot express.
     */
    @Query(value = """
            SELECT * FROM products p
            WHERE p.active = TRUE
              AND (:categoryId IS NULL OR p.category_id = :categoryId)
              AND (CAST(:minPrice AS NUMERIC) IS NULL OR p.price >= :minPrice)
              AND (CAST(:maxPrice AS NUMERIC) IS NULL OR p.price <= :maxPrice)
              AND (CAST(:keyword AS TEXT) IS NULL
                   OR immutable_unaccent(LOWER(p.name || ' ' || p.description))
                      LIKE '%' || immutable_unaccent(LOWER(CAST(:keyword AS TEXT))) || '%')
            """,
            countQuery = """
                    SELECT COUNT(*) FROM products p
                    WHERE p.active = TRUE
                      AND (:categoryId IS NULL OR p.category_id = :categoryId)
                      AND (CAST(:minPrice AS NUMERIC) IS NULL OR p.price >= :minPrice)
                      AND (CAST(:maxPrice AS NUMERIC) IS NULL OR p.price <= :maxPrice)
                      AND (CAST(:keyword AS TEXT) IS NULL
                           OR immutable_unaccent(LOWER(p.name || ' ' || p.description))
                              LIKE '%' || immutable_unaccent(LOWER(CAST(:keyword AS TEXT))) || '%')
                    """,
            nativeQuery = true)
    Page<Product> search(@Param("keyword") String keyword,
                         @Param("categoryId") UUID categoryId,
                         @Param("minPrice") BigDecimal minPrice,
                         @Param("maxPrice") BigDecimal maxPrice,
                         Pageable pageable);

    /**
     * One query for many ids — order-service calls this once per checkout, never per line.
     * Returns inactive products too, flagged, because checkout must reject them by name.
     */
    @Query("SELECT p FROM Product p WHERE p.id IN :ids")
    List<Product> findAllByIdIn(@Param("ids") Collection<UUID> ids);
}
