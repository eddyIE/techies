package vn.techies.ecommerce.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.order.api.dto.OrderDtos.OrderResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ProductReviewsResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ReviewEntry;
import vn.techies.ecommerce.order.api.dto.OrderDtos.ReviewResponse;
import vn.techies.ecommerce.order.api.dto.OrderDtos.WriteReviewsRequest;
import vn.techies.ecommerce.order.domain.Order;
import vn.techies.ecommerce.order.domain.OrderItem;
import vn.techies.ecommerce.order.domain.OrderStatus;
import vn.techies.ecommerce.order.domain.ProductReview;
import vn.techies.ecommerce.order.repository.OrderRepository;
import vn.techies.ecommerce.order.repository.ProductReviewRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writing and reading product reviews.
 *
 * <p>Only someone who bought the product may review it, and that is checked here without
 * leaving the service: the order, its lines and its status are all local. A review is keyed on
 * the order LINE, so buying the same product twice earns two reviews — which is what lets the
 * app show an order as still needing one.
 *
 * <p>The author's name is the order's own recipient name. It is already a snapshot taken at
 * checkout, so a review keeps the name it was written under and no call to identity-service is
 * needed to render one.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository orders;
    private final ProductReviewRepository reviews;

    @Transactional
    public OrderResponse write(UUID orderId, UUID userId, WriteReviewsRequest request) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw ApiException.forbidden("you may only review your own orders");
        }
        // Nothing unpaid is reviewable: the customer has not received it, and a FAILED order's
        // stock went back to the shelf.
        if (order.getStatus() != OrderStatus.CONFIRMED && order.getStatus() != OrderStatus.COMPLETED) {
            throw new ApiException(ErrorCode.ORDER_NOT_REVIEWABLE,
                    "An order in status " + order.getStatus() + " cannot be reviewed");
        }

        Map<UUID, OrderItem> lines = new HashMap<>();
        order.getItems().forEach(i -> lines.put(i.getId(), i));
        Set<UUID> already = Set.copyOf(reviews.reviewedItemIdsIn(lines.keySet()));

        for (ReviewEntry entry : request.reviews()) {
            OrderItem line = lines.get(entry.orderItemId());
            // A line from someone else's order is a 404, not a 403: confirming it exists would
            // leak that it does.
            if (line == null) {
                throw new ApiException(ErrorCode.NOT_FOUND,
                        "Order line " + entry.orderItemId() + " is not part of this order");
            }
            if (already.contains(line.getId())) {
                throw new ApiException(ErrorCode.ALREADY_REVIEWED,
                        "'" + line.getProductName() + "' has already been reviewed on this order");
            }
        }

        for (ReviewEntry entry : request.reviews()) {
            OrderItem line = lines.get(entry.orderItemId());
            reviews.save(ProductReview.write(line.getId(), line.getProductId(), userId,
                    order.getShippingAddress().getRecipientName(), entry.rating(), entry.comment()));
        }
        log.info("Order {} reviewed: {} line(s)", order.getOrderRef(), request.reviews().size());

        Set<UUID> reviewed = Set.copyOf(reviews.reviewedItemIdsIn(lines.keySet()));
        return OrderService.toResponse(order, reviewed);
    }

    @Transactional(readOnly = true)
    public ProductReviewsResponse forProduct(UUID productId, int page, int size) {
        int effectiveSize = Math.min(size <= 0 ? 10 : size, MAX_PAGE_SIZE);
        Page<ProductReview> found = reviews.findByProductIdOrderByCreatedAtDesc(
                productId, PageRequest.of(Math.max(page, 0), effectiveSize));

        List<ReviewResponse> content = found.getContent().stream()
                .map(r -> new ReviewResponse(r.getProductId(), r.getAuthorName(), r.getRating(),
                        r.getComment(), r.getCreatedAt()))
                .toList();

        // Rounded to one decimal: the app shows "4.5", and an unrounded 4.333333 in the payload
        // only invites every client to round it differently.
        double average = Math.round(reviews.averageRatingFor(productId) * 10.0) / 10.0;
        return new ProductReviewsResponse(average, reviews.countByProductId(productId), content);
    }
}
