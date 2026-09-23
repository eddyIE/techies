package vn.techies.ecommerce.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String recipientName;

    @Column(nullable = false, length = 11)
    private String phone;

    @Column(nullable = false)
    private String line1;

    @Column(nullable = false, length = 120)
    private String ward;

    @Column(nullable = false, length = 120)
    private String district;

    @Column(nullable = false, length = 120)
    private String province;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Address create(UUID userId, String recipientName, String phone, String line1,
                                 String ward, String district, String province, boolean isDefault) {
        Address a = new Address();
        a.id = UUID.randomUUID();
        a.userId = userId;
        a.recipientName = recipientName;
        a.phone = phone;
        a.line1 = line1;
        a.ward = ward;
        a.district = district;
        a.province = province;
        a.isDefault = isDefault;
        Instant now = Instant.now();
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
