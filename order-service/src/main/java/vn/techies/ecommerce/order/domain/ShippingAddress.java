package vn.techies.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A point-in-time copy of where the order ships. Embedded in the order rather than referenced,
 * because the user may later edit or delete the address it came from.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ShippingAddress {

    @Column(name = "ship_recipient", nullable = false, length = 120)
    private String recipientName;

    @Column(name = "ship_phone", nullable = false, length = 11)
    private String phone;

    @Column(name = "ship_line1", nullable = false)
    private String line1;

    @Column(name = "ship_ward", nullable = false, length = 120)
    private String ward;

    @Column(name = "ship_district", nullable = false, length = 120)
    private String district;

    @Column(name = "ship_province", nullable = false, length = 120)
    private String province;
}
