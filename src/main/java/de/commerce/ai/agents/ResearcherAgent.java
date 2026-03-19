package de.commerce.ai.agents;

import de.commerce.api.dto.HistoryEntry;
import de.commerce.model.TvProduct;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyses the candidate product list and produces a reasoned recommendation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearcherAgent {

    private static final String SYSTEM_PROMPT = """
            You are a concise TV product analyst. Be terse — no padding, no paraphrasing.
            Respond in the same language as the user query (German or English).

            Structure your response exactly like this (use the headers as-is):
            **Nutzeranforderungen:** (or "User requirements:" in English)
            - bullet: each explicit AND implicit technical requirement derived from the query
            - Derive technical specs from use-case signals: e.g. PS4/Xbox → HDMI, max FullHD (1080p), low input lag matters; gaming → high refresh rate (60 Hz+); kids TV → robust, budget-friendly; sports → fast panel (no blur)
            - Include what the device/use-case technically requires, even if not stated by the user

            **Passende Produkte:** (or "Matching products:" in English)
            - ProductName — one-sentence reason why it fits
            - (list only products that genuinely match; omit the rest)
            """;

    private final ChatLanguageModel chatLanguageModel;

    /**
     * Returns a free-text research finding and recommendation for the given products.
     */
    public String research(String query, List<TvProduct> products, List<HistoryEntry> history) {
        List<ChatMessage> messages = buildMessages(query, products, history);
        String result = chatLanguageModel.generate(messages).content().text();
        log.debug("ResearcherAgent response length: {} chars", result.length());
        return result;
    }

    private List<ChatMessage> buildMessages(String query, List<TvProduct> products,
                                            List<HistoryEntry> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        for (HistoryEntry entry : history) {
            if ("user".equalsIgnoreCase(entry.role())) {
                messages.add(UserMessage.from(entry.content()));
            }
        }
        String productContext = buildProductContext(products);
        String userContent = "Available products:\n" + productContext + "\n\nUser query: " + query;
        messages.add(UserMessage.from(userContent));
        return messages;
    }

    private String buildProductContext(List<TvProduct> products) {
        StringBuilder sb = new StringBuilder();
        for (TvProduct p : products) {
            sb.append("- ")
                    .append(p.name())
                    .append(" | Brand: ").append(p.brand())
                    .append(" | Size: ").append(p.screenSizeInch()).append("\"")
                    .append(" | Panel: ").append(p.panelType())
                    .append(" | Resolution: ").append(p.resolution())
                    .append(" | Price: ").append(p.priceEur()).append(" EUR")
                    .append(" | Rating: ").append(p.ratings())
                    .append('\n');
        }
        return sb.toString();
    }
}
