package vn.techies.ecommerce.catalog.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.BatchRequest;
import vn.techies.ecommerce.catalog.api.dto.CatalogDtos.ProductSnapshot;
import vn.techies.ecommerce.catalog.service.CatalogService;

import java.util.List;

/** Service-to-service only; not routed by the gateway. */
@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
class InternalProductController {

    private final CatalogService catalogService;

    @PostMapping("/batch")
    List<ProductSnapshot> batch(@Valid @RequestBody BatchRequest request) {
        return catalogService.snapshot(request.productIds());
    }
}
