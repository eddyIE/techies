package vn.techies.ecommerce.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reads the catalogue. The assistant never receives product facts from the client — the app
 * sends only a product id, and this service fetches the real data, so a client cannot put
 * invented prices or specifications into the prompt.
 */
@FeignClient(name = "catalog-service")
public interface CatalogClient {

    @GetMapping("/categories")
    List<Category> categories();

    @GetMapping("/products/{id}")
    ProductDetail getProduct(@PathVariable("id") UUID id);

    @GetMapping("/products")
    ProductPage search(@RequestParam(value = "keyword", required = false) String keyword,
                       @RequestParam(value = "categoryId", required = false) UUID categoryId,
                       @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
                       @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
                       @RequestParam("sort") String sort,
                       @RequestParam("page") int page,
                       @RequestParam("size") int size);

    record Category(UUID id, String name, String slug, String imageUrl, int displayOrder) {
    }

    record ProductDetail(UUID id, String name, String slug, String description, BigDecimal price,
                         String thumbnailUrl, UUID categoryId, String categoryName,
                         List<String> images) {
    }

    record ProductSummary(UUID id, String name, String slug, BigDecimal price,
                          String thumbnailUrl, UUID categoryId, String categoryName) {
    }

    record ProductPage(List<ProductSummary> content, int page, int size,
                       long totalElements, int totalPages) {
    }
}
