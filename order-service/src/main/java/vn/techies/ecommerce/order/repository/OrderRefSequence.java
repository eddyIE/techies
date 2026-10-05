package vn.techies.ecommerce.order.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues human-readable order references in the form {@code ORD-100001}.
 *
 * <p>One global sequence rather than a counter per day, so a customer's order list reads as an
 * ascending list of numbers. A Postgres sequence never hands the same value to two callers,
 * so concurrent checkouts cannot share a reference.
 *
 * <p>It runs in its own transaction, which is also how a sequence behaves: the number is
 * consumed even if the surrounding checkout later fails. Gaps are preferable to two orders
 * sharing a reference.
 */
@Component
@RequiredArgsConstructor
public class OrderRefSequence {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        Long counter = jdbc.queryForObject("SELECT nextval('order_ref_seq')", Long.class);
        return "ORD-" + counter;
    }
}
