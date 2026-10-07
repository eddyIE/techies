package vn.techies.ecommerce.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewInput;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryRequest;
import vn.techies.ecommerce.ai.api.dto.ReviewSummaryDtos.ReviewSummaryResponse;
import vn.techies.ecommerce.ai.client.GeminiClient;
import vn.techies.ecommerce.ai.config.GeminiProperties;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * The summary is a pure function of the reviews handed to it, which is the point of taking them
 * in the request body: no database, no fetch, and a test that never spends a live request.
 *
 * <p>{@link GeminiClient} is spied rather than mocked, so the request body asserted on here is
 * the one it really builds.
 */
class ReviewSummaryServiceTest {

    private static final String GOOD_REPLY = """
            {"pros": ["Màn hình sáng", "Pin khỏe"],
             "cons": ["Sạc chậm", "Loa yếu"],
             "verdict": "Phù hợp cho người xem phim nhiều."}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiProperties properties =
            new GeminiProperties("key", "http://localhost", "model", 60, 10, 3, false);
    private final GeminiClient gemini = spy(new GeminiClient(properties, new ObjectMapper()));
    private final ReviewSummaryService service = new ReviewSummaryService(gemini, mapper);

    private static ReviewSummaryRequest request() {
        return new ReviewSummaryRequest("Tai nghe ElecGo Pro", List.of(
                new ReviewInput(5, "Âm thanh rất hay, pin dùng cả ngày."),
                new ReviewInput(4, "Đeo êm nhưng sạc hơi chậm."),
                new ReviewInput(2, "Sạc chậm, dùng hai ngày là hết pin."),
                new ReviewInput(5, "Chống ồn tốt trong tầm giá.")));
    }

    /** Feeds the service a canned model reply, as one text delta. */
    private void modelReplies(String reply) {
        doAnswer(invocation -> {
            Consumer<JsonNode> onEvent = invocation.getArgument(1);
            onEvent.accept(mapper.readTree(mapper.writeValueAsString(Map.of(
                    "event_type", "step.delta",
                    "delta", Map.of("type", "text", "text", reply)))));
            return null;
        }).when(gemini).stream(any(), any());
    }

    private Map<String, Object> bodySent() {
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(gemini).stream(sent.capture(), any());
        return sent.getValue();
    }

    @Test
    @DisplayName("The prompt names the product being reviewed")
    void promptsWithTheProductName() {
        assertThat(ReviewSummaryService.prompt(request())).contains("Tai nghe ElecGo Pro");
    }

    @Test
    @DisplayName("Every supplied comment reaches the prompt")
    void promptsWithEveryComment() {
        String prompt = ReviewSummaryService.prompt(request());

        assertThat(prompt).contains("Sạc chậm, dùng hai ngày là hết pin.");
    }

    @Test
    @DisplayName("Ratings go with the comments, so a 2-star complaint is not read as praise")
    void promptsWithTheRatings() {
        assertThat(ReviewSummaryService.prompt(request())).contains("[2/5]");
    }

    @Test
    @DisplayName("A review with no comment is marked, not sent as a blank line")
    void promptsAroundAMissingComment() {
        ReviewSummaryRequest silent = new ReviewSummaryRequest("X",
                List.of(new ReviewInput(5, null)));

        assertThat(ReviewSummaryService.prompt(silent)).contains("(không có nhận xét)");
    }

    @Test
    @DisplayName("No tools are declared: the summary must come from these reviews only")
    void declaresNoTools() {
        modelReplies(GOOD_REPLY);
        service.summarise(request());

        assertThat(bodySent()).doesNotContainKey("tools");
    }

    @Test
    @DisplayName("Nothing is stored server side, since no continuation chains to this interaction")
    void storesNothing() {
        modelReplies(GOOD_REPLY);
        service.summarise(request());

        assertThat(bodySent()).containsEntry("store", false);
    }

    @Test
    @DisplayName("The rules tell the model to use nothing but the supplied reviews")
    void instructsTheModelNotToInvent() {
        modelReplies(GOOD_REPLY);
        service.summarise(request());

        assertThat(bodySent().get("system_instruction").toString())
                .contains("Chỉ dùng thông tin trong các đánh giá được cung cấp");
    }

    @Test
    @DisplayName("The rules keep a complaint several reviewers share, whatever the average")
    void instructsTheModelToKeepMinorityOpinions() {
        modelReplies(GOOD_REPLY);
        service.summarise(request());

        assertThat(bodySent().get("system_instruction").toString())
                .contains("bắt buộc đưa điểm đó vào cons");
    }

    @Test
    @DisplayName("Pros come back as chips")
    void returnsPros() {
        modelReplies(GOOD_REPLY);

        assertThat(service.summarise(request()).pros()).containsExactly("Màn hình sáng", "Pin khỏe");
    }

    @Test
    @DisplayName("Cons come back as chips")
    void returnsCons() {
        modelReplies(GOOD_REPLY);

        assertThat(service.summarise(request()).cons()).containsExactly("Sạc chậm", "Loa yếu");
    }

    @Test
    @DisplayName("The verdict comes back whole")
    void returnsTheVerdict() {
        modelReplies(GOOD_REPLY);

        assertThat(service.summarise(request()).verdict())
                .isEqualTo("Phù hợp cho người xem phim nhiều.");
    }

    @Test
    @DisplayName("A markdown fence around the JSON is tolerated, because the model adds one anyway")
    void readsAFencedReply() {
        modelReplies("```json\n" + GOOD_REPLY + "\n```");

        assertThat(service.summarise(request()).pros()).hasSize(2);
    }

    @Test
    @DisplayName("Chatter either side of the JSON is tolerated")
    void readsAReplyWithPreamble() {
        modelReplies("Đây là bản tóm tắt:\n" + GOOD_REPLY + "\nHy vọng hữu ích!");

        assertThat(service.summarise(request()).verdict())
                .isEqualTo("Phù hợp cho người xem phim nhiều.");
    }

    @Test
    @DisplayName("Chips are capped, so the section stays skimmable at phone width")
    void capsTheChips() {
        modelReplies("""
                {"pros": ["a", "b", "c", "d", "e", "f"], "cons": [], "verdict": "x"}
                """);

        assertThat(service.summarise(request()).pros())
                .hasSize(ReviewSummaryService.MAX_CHIPS);
    }

    @Test
    @DisplayName("A verdict longer than the cache column is cut to fit it")
    void truncatesALongVerdict() {
        modelReplies("{\"pros\": [], \"cons\": [], \"verdict\": \""
                + "a".repeat(ReviewSummaryService.MAX_VERDICT_LENGTH + 50) + "\"}");

        assertThat(service.summarise(request()).verdict())
                .hasSize(ReviewSummaryService.MAX_VERDICT_LENGTH);
    }

    @Test
    @DisplayName("A reply with no JSON in it is a 502, not an empty summary the caller would cache")
    void refusesAReplyWithoutJson() {
        modelReplies("Xin lỗi, tôi không thể tóm tắt.");

        assertThatThrownBy(() -> service.summarise(request()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("An empty summary is refused rather than cached as the answer")
    void refusesAnEmptySummary() {
        modelReplies("{\"pros\": [], \"cons\": [], \"verdict\": \"\"}");

        assertThatThrownBy(() -> service.summarise(request()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("A Gemini outage is a 502 the caller degrades around")
    void refusesWhenGeminiIsDown() {
        doThrow(new IllegalStateException("429 quota exceeded")).when(gemini).stream(any(), any());

        assertThatThrownBy(() -> service.summarise(request()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("A summary with only cons is still a summary: a bad product has no pros")
    void acceptsConsOnly() {
        modelReplies("{\"pros\": [], \"cons\": [\"Sạc chậm\"], \"verdict\": \"Nên cân nhắc.\"}");

        assertThat(service.summarise(request()).cons()).containsExactly("Sạc chậm");
    }
}
