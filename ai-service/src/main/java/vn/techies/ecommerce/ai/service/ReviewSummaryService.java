package vn.techies.ecommerce.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewInput;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryRequest;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryResponse;
import vn.techies.ecommerce.ai.client.GeminiClient;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a product's reviews into a few pros, a few cons and a one or two sentence verdict.
 *
 * <p>No tools and no web search, deliberately. The point is a summary of <em>these</em>
 * reviews: a model free to search would import opinions from the internet and present them as
 * what ElecGo's own customers said, which is worse than no summary at all.
 *
 * <p>Every failure is a 502. order-service treats that as "serve the previous summary, or
 * nothing" and never fails the product page over it (SPEC-order.md, AI review summary).
 */
@Service
@RequiredArgsConstructor
public class ReviewSummaryService {

    /** Matches `verdict varchar(500)` in order-service's cache. */
    static final int MAX_VERDICT_LENGTH = 500;
    /** Enough to be useful on a phone, few enough to stay chips rather than a list. */
    static final int MAX_CHIPS = 4;

    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryService.class);

    private static final String SYSTEM_INSTRUCTION = """
            Bạn tóm tắt đánh giá sản phẩm cho ứng dụng mua sắm ElecGo. Trả lời bằng tiếng Việt.

            Quy tắc:
            - Chỉ dùng thông tin trong các đánh giá được cung cấp. Không suy đoán, không thêm \
            thông tin từ bên ngoài, không nhắc tên sản phẩm hay tính năng nào không xuất hiện \
            trong các đánh giá đó. Thiếu thông tin thì bỏ qua, không tự điền.
            - pros và cons là các cụm từ ngắn (tối đa 8 từ), không phải câu, không lặp lại \
            nguyên văn một đánh giá.
            - Nếu nhiều người cùng phàn nàn về một điểm, bắt buộc đưa điểm đó vào cons, kể cả \
            khi điểm trung bình cao. Một bản tóm tắt chỉ toàn lời khen sẽ bị người đọc cho là \
            bịa đặt.
            - verdict là một đến hai câu, nêu sản phẩm phù hợp với ai.

            Chỉ trả về JSON đúng dạng sau, không thêm chữ nào khác:
            {"pros": ["..."], "cons": ["..."], "verdict": "..."}
            """;

    private final GeminiClient gemini;
    private final ObjectMapper json;

    public ReviewSummaryResponse summarise(ReviewSummaryRequest request) {
        String reply;
        try {
            reply = gemini.completeText(gemini.textRequest(SYSTEM_INSTRUCTION, prompt(request)));
        } catch (Exception ex) {
            log.warn("Could not summarise reviews for '{}': {}", request.productName(), ex.toString());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Không tạo được bản tóm tắt đánh giá lúc này");
        }
        return parse(reply, request.productName());
    }

    /** The reviews, numbered and rated, exactly as they were supplied. */
    static String prompt(ReviewSummaryRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sản phẩm: ").append(request.productName()).append("\n\n");
        prompt.append("Các đánh giá của khách hàng:\n");

        int index = 1;
        for (ReviewInput review : request.reviews()) {
            prompt.append(index++).append(". [").append(review.rating()).append("/5] ")
                    .append(review.comment() == null ? "(không có nhận xét)" : review.comment())
                    .append('\n');
        }
        return prompt.toString();
    }

    /**
     * Reads the model's JSON, tolerating the markdown fence it sometimes wraps it in.
     *
     * <p>Unparseable output is a 502 rather than an empty summary: an empty one would be cached
     * by the caller against the current review count and never regenerate.
     */
    private ReviewSummaryResponse parse(String reply, String productName) {
        try {
            JsonNode root = json.readTree(unfence(reply));
            List<String> pros = chips(root.path("pros"));
            List<String> cons = chips(root.path("cons"));
            String verdict = root.path("verdict").asText("").strip();

            if (pros.isEmpty() && cons.isEmpty() && verdict.isEmpty()) {
                throw new IllegalStateException("the model returned an empty summary");
            }
            return new ReviewSummaryResponse(pros, cons, truncate(verdict));
        } catch (Exception ex) {
            log.warn("Unparseable review summary for '{}': {}", productName, ex.toString());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Không tạo được bản tóm tắt đánh giá lúc này");
        }
    }

    /** Strips a ```json fence, which the model adds despite being told not to. */
    private static String unfence(String reply) {
        String trimmed = reply.strip();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new IllegalStateException("no JSON object in the reply");
        }
        return trimmed.substring(firstBrace, lastBrace + 1);
    }

    private static List<String> chips(JsonNode array) {
        List<String> chips = new ArrayList<>();
        for (JsonNode entry : array) {
            String chip = entry.asText("").strip();
            if (!chip.isEmpty() && chips.size() < MAX_CHIPS) {
                chips.add(chip);
            }
        }
        return chips;
    }

    private static String truncate(String verdict) {
        return verdict.length() <= MAX_VERDICT_LENGTH
                ? verdict : verdict.substring(0, MAX_VERDICT_LENGTH);
    }
}
