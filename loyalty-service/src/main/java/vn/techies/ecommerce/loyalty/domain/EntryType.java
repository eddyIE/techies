package vn.techies.ecommerce.loyalty.domain;

public enum EntryType {
    /** Points credited because an order reached COMPLETED. Always positive. */
    ORDER_EARN,
    /** Points spent claiming a gift. Always negative. */
    GIFT_SPEND
}
