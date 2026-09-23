package vn.techies.ecommerce.catalog.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryResponse(UUID id, String name, String slug, String imageUrl, int displayOrder) {
    }

    /** List/search row: deliberately lighter than the detail payload. */
    public record ProductSummary(UUID id, String name, String slug, BigDecimal price,
                                 String thumbnailUrl, UUID categoryId, String categoryName) {
    }

    public record ProductDetail(UUID id, String name, String slug, String description,
                                BigDecimal price, String thumbnailUrl, UUID categoryId,
                                String categoryName, List<String> images) {
    }

    /** Page envelope shared by every paginated endpoint in the project. */
    public record PageResponse<T>(List<T> content, int page, int size,
                                  long totalElements, int totalPages) {
    }

    public record BatchRequest(@NotEmpty List<UUID> productIds) {
    }

    /** Carries {@code active} so order-service can reject a checkout naming a delisted product. */
    public record ProductSnapshot(UUID id, String name, BigDecimal price,
                                  String thumbnailUrl, boolean active) {
    }
}
