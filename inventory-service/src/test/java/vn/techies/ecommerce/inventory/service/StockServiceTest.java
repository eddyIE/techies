package vn.techies.ecommerce.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.inventory.AbstractPostgresTest;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.DeductResponse;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockLine;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockMovementRequest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StockServiceTest extends AbstractPostgresTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private JdbcTemplate jdbc;

    /** Inserts a stock row with a known quantity, isolated from the seeded catalog. */
    private UUID stockedProduct(int available) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_items (product_id, available, version, updated_at) "
                + "VALUES (?, ?, 0, NOW())", productId, available);
        return productId;
    }

    private int availableOf(UUID productId) {
        Integer value = jdbc.queryForObject(
                "SELECT available FROM stock_items WHERE product_id = ?", Integer.class, productId);
        return value == null ? -1 : value;
    }

    private String ref() {
        // order_ref is VARCHAR(20), matching the ORD-yyyyMMdd-NNNN format order-service emits.
        return "ORD-" + System.nanoTime() % 100_000_000L + "-" + (int) (Math.random() * 1000);
    }

    private StockMovementRequest request(String orderRef, UUID productId, int qty) {
        return new StockMovementRequest(orderRef, List.of(new StockLine(productId, qty)));
    }

    @Test
    @DisplayName("deduct takes stock and records a movement")
    void deductTakesStock() {
        UUID product = stockedProduct(10);

        DeductResponse response = stockService.deduct(request(ref(), product, 3));

        assertThat(response.deducted()).isTrue();
        assertThat(response.movementId()).isNotNull();
        assertThat(availableOf(product)).isEqualTo(7);
    }

    @Test
    @DisplayName("a multi-line deduct is all-or-nothing: one short line rolls back the others")
    void deductIsAllOrNothing() {
        UUID plentiful = stockedProduct(10);
        UUID scarce = stockedProduct(1);

        assertThatThrownBy(() -> stockService.deduct(new StockMovementRequest(ref(),
                List.of(new StockLine(plentiful, 2), new StockLine(scarce, 5)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertThat(availableOf(plentiful)).as("rolled back, not partially deducted").isEqualTo(10);
        assertThat(availableOf(scarce)).isEqualTo(1);
    }

    @Test
    @DisplayName("deduct then restore returns stock to exactly its original value")
    void restoreIsExactInverse() {
        UUID product = stockedProduct(10);
        String orderRef = ref();

        stockService.deduct(request(orderRef, product, 4));
        assertThat(availableOf(product)).isEqualTo(6);

        stockService.restore(request(orderRef, product, 4));
        assertThat(availableOf(product)).as("exact inverse").isEqualTo(10);
    }

    @Test
    @DisplayName("deduct is idempotent on the order reference, so a retry cannot double-charge stock")
    void deductIsIdempotent() {
        UUID product = stockedProduct(10);
        String orderRef = ref();

        DeductResponse first = stockService.deduct(request(orderRef, product, 3));
        DeductResponse second = stockService.deduct(request(orderRef, product, 3));

        assertThat(second.movementId()).isEqualTo(first.movementId());
        assertThat(availableOf(product)).as("stock moved once, not twice").isEqualTo(7);
    }

    @Test
    @DisplayName("restore is idempotent too")
    void restoreIsIdempotent() {
        UUID product = stockedProduct(10);
        String orderRef = ref();
        stockService.deduct(request(orderRef, product, 3));

        stockService.restore(request(orderRef, product, 3));
        stockService.restore(request(orderRef, product, 3));

        assertThat(availableOf(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("restoring stock that was never deducted is refused, not silently accepted")
    void restoreWithoutDeductIsRefused() {
        UUID product = stockedProduct(10);

        assertThatThrownBy(() -> stockService.restore(request(ref(), product, 3)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOTHING_TO_RESTORE);

        assertThat(availableOf(product)).as("no phantom inventory created").isEqualTo(10);
    }

    @Test
    @DisplayName("the same product listed twice is merged, so it cannot oversell itself")
    void mergesRepeatedProductLines() {
        UUID product = stockedProduct(5);

        assertThatThrownBy(() -> stockService.deduct(new StockMovementRequest(ref(),
                List.of(new StockLine(product, 3), new StockLine(product, 3)))))
                .isInstanceOf(ApiException.class);

        assertThat(availableOf(product)).as("6 > 5, so nothing moved").isEqualTo(5);
    }

    @Test
    @DisplayName("a zero-stock product reports inStock=false rather than erroring")
    void readsZeroStock() {
        UUID product = stockedProduct(0);

        var response = stockService.get(product);

        assertThat(response.available()).isZero();
        assertThat(response.inStock()).isFalse();
    }

    @Test
    @DisplayName("OVERSELL PROOF: ten threads race for the last unit and exactly one wins")
    void concurrentDeductsCannotOversell() throws Exception {
        int threads = 10;
        UUID product = stockedProduct(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Future<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String orderRef = "ORD-RACE-" + i;
                Callable<Void> task = () -> {
                    // All threads block here, then contend simultaneously.
                    startGate.await();
                    try {
                        stockService.deduct(request(orderRef, product, 1));
                        succeeded.incrementAndGet();
                    } catch (Exception expectedForLosers) {
                        rejected.incrementAndGet();
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }

            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("exactly one buyer gets the last unit").isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(availableOf(product)).as("never negative").isZero();
    }

    @Test
    @DisplayName("the CHECK constraint is a real backstop against negative stock")
    void checkConstraintPreventsNegativeStock() {
        UUID product = stockedProduct(1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE stock_items SET available = available - 5 WHERE product_id = ?", product))
                .hasMessageContaining("ck_stock_available_non_negative");
    }
}
