package vn.techies.ecommerce.ai.service;

import org.springframework.stereotype.Component;
import vn.techies.ecommerce.ai.client.CatalogClient;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Builds the system instruction for one PDP conversation.
 *
 * <p>The product block is assembled server-side from the real catalogue. The most important
 * line is the anti-invention rule: the seeded descriptions are thin, so the model will be
 * asked about RAM, battery, warranty and delivery that simply are not in the data. Without
 * an explicit instruction it answers confidently and wrongly, which is worse than refusing.
 */
@Component
public class SystemPromptBuilder {

    private static final NumberFormat VND = NumberFormat.getInstance(new Locale("vi", "VN"));

    public String build(CatalogClient.ProductDetail product, Integer availableStock) {
        String stock = availableStock == null ? "không rõ"
                : availableStock > 0 ? "còn hàng" : "hết hàng";

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
                1. Chỉ sử dụng thông tin được cung cấp ở trên. TUYỆT ĐỐI KHÔNG bịa ra thông số
                   kỹ thuật, dung lượng pin, RAM, bộ nhớ, thời gian bảo hành, khuyến mãi hay
                   thời gian giao hàng nếu chúng không có trong mô tả.
                2. Nếu khách hỏi điều bạn không biết, hãy nói thẳng là bạn không có thông tin đó
                   và gợi ý khách liên hệ cửa hàng. Không suy đoán.
                3. Luôn trả lời bằng tiếng Việt, thân thiện và ngắn gọn: tối đa 2-3 câu.
                   Khách đang xem trên điện thoại.
                4. Giá luôn bằng VND. Không quy đổi sang ngoại tệ.
                5. Khi khách hỏi về SẢN PHẨM KHÁC hoặc muốn so sánh, gợi ý, tìm theo giá hoặc
                   danh mục, hãy dùng công cụ search_products. Không tự liệt kê sản phẩm từ
                   trí nhớ, vì bạn không biết kho hàng hiện tại.
                6. Chỉ nói về sản phẩm và cửa hàng Techies. Nếu khách hỏi chuyện ngoài lề,
                   từ chối lịch sự và hướng khách về sản phẩm.
                """.formatted(
                product.name(),
                VND.format(product.price()),
                product.categoryName(),
                stock,
                product.description());
    }
}
