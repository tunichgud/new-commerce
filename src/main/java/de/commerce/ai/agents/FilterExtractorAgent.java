package de.commerce.ai.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.commerce.model.SearchFilters;
import de.commerce.model.SortField;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Extracts structured {@link SearchFilters} from a natural-language user query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterExtractorAgent {

    private static final String SYSTEM_PROMPT = """
            Extract structured search filters from the user query. \
            Respond ONLY with valid JSON matching this schema exactly — no markdown, no extra text:
            {"brand":null,"screenSizeInch":null,"panelType":null,"maxPriceEur":null,"minRating":null,"sortFields":null}
            Set only the fields that are explicitly or implicitly present in the query. \
            Recognize German and English naturally.

            sortFields example: [{"field":"priceEur","order":"asc"},{"field":"ratings","order":"desc"}]
            Only set sortFields if user explicitly mentions sorting preference (e.g., "cheapest", "best rated", "sorted by price").

            Examples:
            "55 Zoll OLED unter 800 Euro" -> {"brand":null,"screenSizeInch":55,"panelType":"OLED","maxPriceEur":800.0,"minRating":null,"sortFields":null}
            "cheap 4K Samsung TV" -> {"brand":"Samsung","screenSizeInch":null,"panelType":null,"maxPriceEur":null,"minRating":null,"sortFields":null}
            "cheapest OLED" -> {"brand":null,"screenSizeInch":null,"panelType":"OLED","maxPriceEur":null,"minRating":null,"sortFields":[{"field":"priceEur","order":"asc"}]}
            "best rated 55 inch" -> {"brand":null,"screenSizeInch":55,"panelType":null,"maxPriceEur":null,"minRating":null,"sortFields":[{"field":"ratings","order":"desc"}]}
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    /**
     * Parses the user query and returns extracted filters.
     * Returns an empty {@link SearchFilters} (all fields null) if parsing fails — never throws.
     */
    public SearchFilters extract(String query) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(query)
        );
        String raw = chatLanguageModel.generate(messages).content().text();
        log.debug("FilterExtractorAgent raw response: {}", raw);
        return parseFilters(raw);
    }

    private SearchFilters parseFilters(String raw) {
        try {
            String cleaned = JsonParsingUtil.stripMarkdownCodeBlock(raw);
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<>() {});

            String brand = map.get("brand") != null ? map.get("brand").toString() : null;
            Integer screenSize = asInteger(map.get("screenSizeInch"));
            String panelType = map.get("panelType") != null ? map.get("panelType").toString() : null;
            Double maxPrice = asDouble(map.get("maxPriceEur"));
            Double minRating = asDouble(map.get("minRating"));
            List<SortField> sortFields = parseSortFields(map.get("sortFields"));

            return new SearchFilters(brand, screenSize, panelType, maxPrice, minRating, sortFields);
        } catch (Exception e) {
            log.warn("FilterExtractorAgent could not parse LLM response, returning empty filters. Response: {}", raw, e);
            return new SearchFilters(null, null, null, null, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SortField> parseSortFields(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) return null;
        try {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        String field = m.get("field") != null ? m.get("field").toString() : null;
                        String order = m.get("order") != null ? m.get("order").toString() : "asc";
                        return new SortField(field, order);
                    })
                    .filter(sf -> sf.field() != null)
                    .toList();
        } catch (Exception e) {
            log.warn("FilterExtractorAgent could not parse sortFields: {}", raw, e);
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
