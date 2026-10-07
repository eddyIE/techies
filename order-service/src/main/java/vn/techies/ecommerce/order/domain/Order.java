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

    /** Snapshot: withdrawing or editing a coupon later must not change a past order. */
    @Column(name = "coupon_code", length = 32)
    private String couponCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_source", nullable = false, length = 16)
    private DiscountSource discountSource;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal discount;

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

    /** The payment provider's transaction id, once the app reports a settled payment. */
    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public static Order pending(String orderRef, UUID userId, ShippingAddress address,
                                PaymentMethod paymentMethod, BigDecimal subtotal,
                                BigDecimal shippingFee, String couponCode, BigDecimal discount,
                                DiscountSource discountSource) {
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
        order.couponCode = couponCode;
        order.discount = discount;
        order.discountSource = discountSource;
        order.total = subtotal.add(shippingFee).subtract(discount);
        Instant now = Instant.now();
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void addItem(UUID productId, String productName, BigDecimal unitPrice, int quantity,
                        String thumbnailUrl) {
        items.add(OrderItem.create(this, productId, productName, unitPrice, quantity, thumbnailUrl));
    }

    /** Stock is held; hand the customer over to the payment screen. */
    public void awaitPayment() {
        this.status = OrderStatus.AWAITING_PAYMENT;
        touch();
    }

    public void confirm(String paymentRef) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentStatus = PaymentStatus.PAID;
        if (paymentRef != null && !paymentRef.isBlank()) {
            this.paymentRef = paymentRef;
        }
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

    /**
     * Fulfilled. Payment status is left alone: an order only reaches here from CONFIRMED,
     * which is already PAID.
     */
    public void complete() {
        this.status = OrderStatus.COMPLETED;
        touch();
    }

    /** Whether compensation has to hand a voucher back to loyalty-service. */
    public boolean usedLoyaltyVoucher() {
        return discountSource == DiscountSource.LOYALTY_VOUCHER;
    }

    public boolean isCancellable() {
        return status == OrderStatus.CONFIRMED;
    }

    /**
     * Whether this order is still waiting for the customer to pay. COD orders never are:
     * the saga confirms them and the money moves at the door.
     */
    public boolean isAwaitingPayment() {
        return status == OrderStatus.AWAITING_PAYMENT;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
