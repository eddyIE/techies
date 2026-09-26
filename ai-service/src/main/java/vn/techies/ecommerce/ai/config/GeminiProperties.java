package vn.techies.ecommerce.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey             from GEMINI_API_KEY. Absent means the assistant is disabled
 *                           rather than the service failing to start.
 * @param maxHistoryMessages conversation turns accepted per request. Caps token spend and
 *                           limits how much a client can push into a prompt.
 * @param maxProducts        product cards returned to the chat.
 */
@ConfigurationProperties(prefix = "techies.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String model,
        int timeoutSeconds,
        int maxHistoryMessages,
        int maxProducts) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
