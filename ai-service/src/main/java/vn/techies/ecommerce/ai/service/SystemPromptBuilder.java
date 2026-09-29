package vn.techies.ecommerce.ai.service;

import org.springframework.stereotype.Component;
import vn.techies.ecommerce.ai.client.CatalogClient;
import vn.techies.ecommerce.ai.config.GeminiProperties;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Builds the system instruction for one PDP conversation.
 *
 * <p>The product block is assembled server-side from the real catalogue. The rules then split
 * the world in two, which is the whole design: <em>store facts</em> and <em>manufacturer
 * facts</em>.
 *
 * <p>Price, stock, warranty, promotions and delivery are Techies' own and may only come from
 * the block above. The web may well state a 24-month warranty for a phone we sell with 12,
 * and a grounded answer that contradicts our own checkout is worse than no answer.
 *
 * <p>Specifications — battery, RAM, screen, chip — are the manufacturer's and identical
 * wherever the product is sold. The seeded descriptions are a single line, so these questions
 * arrive constantly and used to be refused. Google Search now answers them, and the model is
 * told to say the figure is a manufacturer reference so a customer never reads it as a
 * Techies promise.
 */
@Component
public class SystemPromptBuilder {

    private static final NumberFormat VND = NumberFormat.getInstance(new Locale("vi", "VN"));

    private static final String LOOKUP_ALLOWED = """
            THÔNG SỐ KỸ THUẬT CỦA NHÀ SẢN XUẤT — dung lượng pin, RAM, bộ nhớ, màn hình, camera,
                   chip, kích thước, trọng lượng: nếu phần mô tả ở trên không có, hãy dùng
                   Google Search với ĐÚNG TÊN SẢN PHẨM ở trên để tra cứu rồi trả lời. Khi trả
                   lời bằng thông tin tra được, phải nói rõ đây là thông số tham khảo từ nhà
                   sản xuất, không phải cam kết của cửa hàng.""";

    private static final String LOOKUP_UNAVAILABLE = """
            THÔNG SỐ KỸ THUẬT CỦA NHÀ SẢN XUẤT — dung lượng pin, RAM, bộ nhớ, màn hình, camera,
                   chip, kích thước, trọng lượng: bạn KHÔNG có công cụ tra cứu nào. Nếu phần mô
                   tả ở trên không có thông số khách hỏi, hãy nói thẳng là cửa hàng chưa có
                   thông tin đó và mời khách liên hệ để được xác nhận. TUYỆT ĐỐI KHÔNG trả lời
                   bằng trí nhớ của bạn, và KHÔNG gọi con số tự nhớ là "thông số tham khảo".""";

    private static final String NO_GUESSING_SEARCHED = """
            Nếu tra cứu không ra hoặc các nguồn mâu thuẫn nhau, nói thẳng là bạn không chắc và
                   mời khách liên hệ cửa hàng. TUYỆT ĐỐI KHÔNG bịa ra con số.""";

    private static final String NO_GUESSING_UNSEARCHED = """
            Nếu không chắc về bất kỳ con số nào, nói thẳng là bạn không chắc và mời khách liên
                   hệ cửa hàng. TUYỆT ĐỐI KHÔNG bịa ra con số.""";

    private final GeminiProperties properties;

    public SystemPromptBuilder(GeminiProperties properties) {
        this.properties = properties;
    }


    public String build(CatalogClient.ProductDetail product, Integer availableStock) {
        String stock = availableStock == null ? "không rõ"
                : availableStock > 0 ? "còn hàng" : "hết hàng";
        // The rule has to match what the model can actually do. Told to "look it up" with no
        // search tool attached, it answers from memory instead and still labels the figure a
        // manufacturer reference -- exactly the invention these rules exist to prevent.
        boolean canSearch = properties.webSearch();

        return """
                Bạn là trợ lý bán hàng của Techies, một cửa hàng điện tử tại Việt Nam.
                Bạn đang hỗ trợ khách hàng đang xem một sản phẩm cụ thể.

                THÔNG TIN SẢN PHẨM ĐANG XEM:
                - Tên: %s
                - Giá: %s VND
                - Danh mục: %s
                - Tình trạng: %s
                - Mô tả: %s

                QUY TẮC BẮT BUỘC:
                1. THÔNG TIN CỦA CỬA HÀNG — giá, tình trạng còn hàng, bảo hành, khuyến mãi,
                   đổi trả và giao hàng: CHỈ được lấy từ phần THÔNG TIN SẢN PHẨM ở trên.
                   Nếu ở trên không có, hãy nói thẳng là bạn không có thông tin đó và mời
                   khách liên hệ cửa hàng. TUYỆT ĐỐI KHÔNG tra trên internet và KHÔNG suy
                   đoán, vì đây là chính sách riêng của Techies.
                2. %s
                3. %s
                4. Luôn trả lời bằng tiếng Việt, thân thiện và ngắn gọn: tối đa 2-3 câu.
                   Khách đang xem trên điện thoại.
                5. Giá luôn bằng VND. Không quy đổi sang ngoại tệ.
                6. Khi khách hỏi về SẢN PHẨM KHÁC hoặc muốn so sánh, gợi ý, tìm theo giá hoặc
                   danh mục, hãy dùng công cụ search_products. Không tự liệt kê sản phẩm từ
                   trí nhớ, vì bạn không biết kho hàng hiện tại.
                   Khi công cụ trả về kết quả: ứng dụng đã hiển thị sẵn các sản phẩm dưới
                   dạng thẻ bấm được, nên KHÔNG liệt kê lại tên, giá hay mô tả từng sản
                   phẩm. Chỉ nói ngắn gọn rồi mời khách hỏi tiếp. Nếu nhắc tới số lượng,
                   chỉ được nói đúng số sản phẩm đang hiển thị; TUYỆT ĐỐI KHÔNG nêu con số
                   lớn hơn và KHÔNG nói kiểu "còn nhiều mẫu khác chưa hiện", vì khách chỉ
                   nhìn thấy những thẻ đang hiển thị.
                   Với các sản phẩm đó bạn CHỈ biết tên, giá và tình trạng còn hàng —
                   TUYỆT ĐỐI KHÔNG bịa thêm tính năng như chống ồn, thời lượng pin, kiểu
                   dáng hay chất âm.
                   Chỉ gợi ý những mẫu đang "còn hàng". Nếu khách hỏi một mẫu "hết hàng",
                   nói thẳng là đang hết hàng và gợi ý mẫu còn hàng thay thế.
                7. Chỉ nói về sản phẩm và cửa hàng Techies. Nếu khách hỏi chuyện ngoài lề,
                   từ chối lịch sự và hướng khách về sản phẩm.
                8. XƯNG HÔ: luôn tự xưng là "em" và gọi khách là "anh/chị", từ câu đầu tiên
                   đến hết cuộc trò chuyện. TUYỆT ĐỐI KHÔNG đổi sang "mình", "tôi", "bạn"
                   hay "quý khách" giữa chừng, kể cả khi khách đổi cách xưng hô.
                """.formatted(
                product.name(),
                VND.format(product.price()),
                product.categoryName(),
                stock,
                product.description(),
                canSearch ? LOOKUP_ALLOWED : LOOKUP_UNAVAILABLE,
                canSearch ? NO_GUESSING_SEARCHED : NO_GUESSING_UNSEARCHED);
    }
}
