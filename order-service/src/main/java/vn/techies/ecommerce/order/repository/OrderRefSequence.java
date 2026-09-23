package vn.techies.ecommerce.order.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Issues human-readable order references in the form {@code ORD-yyyyMMdd-NNNN}.
 *
 * <p>The counter upsert is atomic, so two concurrent checkouts cannot be handed the same
 * reference. It runs in its own transaction: the number is consumed even if the surrounding
 * checkout later fails, which is preferable to two orders sharing a reference.
 */
@Component
@RequiredArgsConstructor
public class OrderRefSequence {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        Integer counter = jdbc.queryForObject("""
                INSERT INTO order_ref_counters (day, counter) VALUES (CURRENT_DATE, 1)
                ON CONFLICT (day) DO UPDATE SET counter = order_ref_counters.counter + 1
                RETURNING counter
                """, Integer.class);

        return "ORD-%s-%04d".formatted(LocalDate.now().format(DAY), counter == null ? 1 : counter);
    }
}
