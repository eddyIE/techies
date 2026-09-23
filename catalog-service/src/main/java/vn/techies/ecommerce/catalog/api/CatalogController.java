package vn.techies.ecommerce.catalog.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.CategoryResponse;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.PageResponse;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductDetail;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductSummary;
import vn.techies.ecommerce.catalog.service.CatalogService;
import vn.techies.ecommerce.catalog.service.ProductSort;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/categories")
    List<CategoryResponse> categories() {
        return catalogService.listCategories();
    }

    @GetMapping("/products")
    PageResponse<ProductSummary> products(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort) {
        return catalogService.search(keyword, categoryId, minPrice, maxPrice, page, size, sort);
    }

    @GetMapping("/products/{id}")
    ProductDetail product(@PathVariable UUID id) {
        return catalogService.detail(id);
    }
}
