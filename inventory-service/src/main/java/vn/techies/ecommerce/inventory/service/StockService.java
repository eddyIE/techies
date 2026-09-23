package vn.techies.ecommerce.inventory.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.DeductResponse;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.RestoreResponse;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockLine;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockMovementRequest;
import vn.techies.ecommerce.inventory.api.dto.StockDtos.StockResponse;
import vn.techies.ecommerce.inventory.domain.MovementType;
import vn.techies.ecommerce.inventory.domain.StockItem;
import vn.techies.ecommerce.inventory.domain.StockMovement;
import vn.techies.ecommerce.inventory.repository.StockItemRepository;
import vn.techies.ecommerce.inventory.repository.StockMovementRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockItemRepository stockItems;
    private final StockMovementRepository movements;

    @Transactional(readOnly = true)
    public StockResponse get(UUID productId) {
        StockItem item = stockItems.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No stock record for product " + productId));
        return new StockResponse(item.getProductId(), item.getAvailable(), item.isInStock());
    }

    /**
     * Takes stock for an order, all-or-nothing.
     *
     * <p>Idempotent on the order reference: a retried call (order-service retries once on
     * timeout) returns the original movement and moves no stock. If any line is short, the
     * whole transaction rolls back — a partial deduction must never exist.
     */
    @Transactional
    public DeductResponse deduct(StockMovementRequest request) {
        var existing = movements.findByOrderRefAndType(request.orderRef(), MovementType.DEDUCT);
        if (existing.isPresent()) {
            log.info("Deduct for {} already applied; returning existing movement", request.orderRef());
            return new DeductResponse(request.orderRef(), existing.get().getId(), true);
        }

        Map<UUID, Integer> merged = mergeLines(request.items());
        List<UUID> insufficient = new ArrayList<>();

        for (Map.Entry<UUID, Integer> line : merged.entrySet()) {
            int updated = stockItems.deductIfAvailable(line.getKey(), line.getValue());
            if (updated == 0) {
                insufficient.add(line.getKey());
            }
        }

        if (!insufficient.isEmpty()) {
            // Throwing rolls back every line already decremented in this transaction.
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Insufficient stock for products: " + insufficient);
        }

        StockMovement movement = StockMovement.create(request.orderRef(), MovementType.DEDUCT);
        merged.forEach(movement::addItem);
        movements.save(movement);

        return new DeductResponse(request.orderRef(), movement.getId(), true);
    }

    /**
     * The compensating transaction: returns stock taken by a previous deduct.
     *
     * <p>Used for both failure paths — payment declined during checkout, and cancellation of a
     * confirmed order. Restoring stock that was never deducted would invent inventory, so that
     * is a 404 rather than a silent success.
     */
    @Transactional
    public RestoreResponse restore(StockMovementRequest request) {
        if (!movements.existsByOrderRefAndType(request.orderRef(), MovementType.DEDUCT)) {
            throw new ApiException(ErrorCode.NOTHING_TO_RESTORE,
                    "No stock was ever deducted for order " + request.orderRef());
        }

        var existing = movements.findByOrderRefAndType(request.orderRef(), MovementType.RESTORE);
        if (existing.isPresent()) {
            log.info("Restore for {} already applied; returning existing movement", request.orderRef());
            return new RestoreResponse(request.orderRef(), existing.get().getId(), true);
        }

        Map<UUID, Integer> merged = mergeLines(request.items());
        merged.forEach(stockItems::restore);

        StockMovement movement = StockMovement.create(request.orderRef(), MovementType.RESTORE);
        merged.forEach(movement::addItem);
        movements.save(movement);

        log.info("Restored stock for order {} ({} lines)", request.orderRef(), merged.size());
        return new RestoreResponse(request.orderRef(), movement.getId(), true);
    }

    /**
     * Collapses repeated product ids into one line. Without this, the same product listed
     * twice would be checked against stock twice independently and could oversell.
     */
    private static Map<UUID, Integer> mergeLines(List<StockLine> lines) {
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        for (StockLine line : lines) {
            merged.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return merged;
    }
}
