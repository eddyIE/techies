package vn.techies.ecommerce.order.service.payment;

public record PaymentResult(boolean approved, String reason) {

    // Named approve/decline rather than approved/declined: a static method cannot share a
    // signature with a record's generated accessor.
    public static PaymentResult approve() {
        return new PaymentResult(true, "Approved");
    }

    public static PaymentResult decline(String reason) {
        return new PaymentResult(false, reason);
    }
}
