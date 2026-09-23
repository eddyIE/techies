package vn.techies.ecommerce.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "order_ref", nullable = false, length = 20)
    private String orderRef;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 32)
    private FailureCode failureCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal shippingFee;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Embedded
    private ShippingAddress shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 16)
    private PaymentStatus paymentStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public static Order pending(String orderRef, UUID userId, ShippingAddress address,
                                PaymentMethod paymentMethod, BigDecimal subtotal,
                                BigDecimal shippingFee) {
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.orderRef = orderRef;
        order.userId = userId;
        order.status = OrderStatus.PENDING;
        order.shippingAddress = address;
        order.paymentMethod = paymentMethod;
        order.paymentStatus = PaymentStatus.PENDING;
        order.subtotal = subtotal;
        order.shippingFee = shippingFee;
        order.total = subtotal.add(shippingFee);
        Instant now = Instant.now();
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void addItem(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        items.add(OrderItem.create(this, productId, productName, unitPrice, quantity));
    }

    public void confirm() {
        this.status = OrderStatus.CONFIRMED;
        this.paymentStatus = PaymentStatus.PAID;
        touch();
    }

    public void fail(FailureCode code) {
        this.status = OrderStatus.FAILED;
        this.failureCode = code;
        if (code == FailureCode.PAYMENT_FAILED) {
            this.paymentStatus = PaymentStatus.DECLINED;
        }
        touch();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
        if (this.paymentStatus == PaymentStatus.PAID) {
            this.paymentStatus = PaymentStatus.REFUNDED;
        }
        touch();
    }

    public boolean isCancellable() {
        return status == OrderStatus.CONFIRMED;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
