package vn.techies.ecommerce.catalog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.CategoryResponse;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.PageResponse;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductDetail;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductSnapshot;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductSummary;
import vn.techies.ecommerce.catalog.domain.Product;
import vn.techies.ecommerce.catalog.domain.ProductImage;
import vn.techies.ecommerce.catalog.repository.CategoryRepository;
import vn.techies.ecommerce.catalog.repository.ProductImageRepository;
import vn.techies.ecommerce.catalog.repository.ProductRepository;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    /** Guards against a client asking for the whole catalog in one page. */
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final ProductImageRepository images;

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categories.findAllByOrderByDisplayOrderAsc().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName(), c.getSlug(),
                        c.getImageUrl(), c.getDisplayOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> search(String keyword, UUID categoryId,
                                               BigDecimal minPrice, BigDecimal maxPrice,
                                               int page, int size, ProductSort sort) {
        // Clamp rather than reject: an oversized size is a client bug, not a reason to 400.
        int effectiveSize = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 0);

        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();

        Page<Product> found = products.search(trimmed, categoryId, minPrice, maxPrice,
                PageRequest.of(effectivePage, effectiveSize, sort.sort()));

        return new PageResponse<>(
                found.getContent().stream().map(CatalogService::toSummary).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(UUID id) {
        // Inactive products are invisible here: a delisted product must 404, not render.
        Product product = products.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

        List<String> urls = images.findByProductIdOrderByDisplayOrderAsc(id).stream()
                .map(ProductImage::getUrl)
                .toList();

        return new ProductDetail(product.getId(), product.getName(), product.getSlug(),
                product.getDescription(), product.getPrice(), product.getThumbnailUrl(),
                product.getCategory().getId(), product.getCategory().getName(), urls);
    }

    /** Price and name snapshot for checkout. Inactive products come back flagged, not filtered. */
    @Transactional(readOnly = true)
    public List<ProductSnapshot> snapshot(Collection<UUID> ids) {
        return products.findAllByIdIn(ids).stream()
                .map(p -> new ProductSnapshot(p.getId(), p.getName(), p.getPrice(),
                        p.getThumbnailUrl(), p.isActive()))
                .toList();
    }

    private static ProductSummary toSummary(Product p) {
        return new ProductSummary(p.getId(), p.getName(), p.getSlug(), p.getPrice(),
                p.getThumbnailUrl(), p.getCategory().getId(), p.getCategory().getName());
    }
}
