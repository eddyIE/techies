package vn.techies.ecommerce.catalog.service;

import org.springframework.data.domain.Sort;

/** The sort options the mobile app offers. Anything else is rejected rather than guessed at. */
public enum ProductSort {

    NEWEST(Sort.by(Sort.Direction.DESC, "created_at")),
    PRICE_ASC(Sort.by(Sort.Direction.ASC, "price")),
    PRICE_DESC(Sort.by(Sort.Direction.DESC, "price")),
    NAME_ASC(Sort.by(Sort.Direction.ASC, "name"));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
