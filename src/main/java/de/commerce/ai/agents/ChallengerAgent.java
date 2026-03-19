package de.commerce.ai.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.commerce.api.dto.HistoryEntry;
import de.commerce.model.SortField;
import de.commerce.model.TvProduct;
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
 * Critically reviews the researcher's findings and highlights overlooked aspects,
 * better alternatives, or proposes structured search refinements / clarification questions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengerAgent {

    private static final String SYSTEM_PROMPT = """
            You are a critical reviewer. Be direct and brief.
            Respond in the same language as the user query (German or English).

            Output ONLY valid JSON (no markdown):
            {
              "critique": "bullet-point critique, max 4 points",
              "searchRefinement": {
                "query": null,
                "sortFields": null,
                "filterChanges": null,
                "addKeywords": null,
                "removeKeywords": null,
                "diversifyPrices": false
              },
              "clarificationQuestion": null
            }

            Rules:
            - critique: max 4 bullets, weaknesses, overlooked alternatives, price/value
            - searchRefinement: suggest improvements. Set only fields that should change. Use null for unchanged fields.
              - query: new search query if a completely different angle would help
              - sortFields: e.g. [{"field":"priceEur","order":"asc"}] if price sorting would help
              - filterChanges: map of filter field to new value (null value = remove filter)
              - addKeywords/removeKeywords: for query expansion/reduction
              - diversifyPrices: true ONLY if no price info from user AND diverse price ranges would help
            - clarificationQuestion: ONLY if critical info is missing (price, size, use case) AND it has NOT already been asked in the conversation history. Otherwise null.
              If conversation history is provided and already contains answers to the key unknowns (budget, size, use case), set clarificationQuestion=null.
              Never ask about something the user already answered.
            - Set clarificationQuestion OR searchRefinement, not both in the same response.
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    /**
     * Returns a structured critique of the researcher's findings along with optional
     * refinement suggestions or a clarification question.
     *
     * @param history conversation history — passed so the Challenger does not re-ask questions
     *                already answered by the user
     */
    public ChallengerResult challenge(String query, List<TvProduct> products,
                                      String researcherFindings, List<HistoryEntry> history) {
        List<ChatMessage> messages = buildMessages(query, products, researcherFindings, history);
        String raw = chatLanguageModel.generate(messages).content().text();
        log.debug("ChallengerAgent raw response length: {} chars", raw.length());
        return parseResult(raw);
    }

    private List<ChatMessage> buildMessages(String query, List<TvProduct> products,
                                            String researcherFindings, List<HistoryEntry> history) {
        String productContext = buildProductContext(products);
        StringBuilder sb = new StringBuilder();

        if (history != null && !history.isEmpty()) {
            sb.append("Conversation history (do NOT ask about anything already answered here):\n");
            history.forEach(e -> sb.append(e.role()).append(": ").append(e.content()).append('\n'));
            sb.append('\n');
        }

        sb.append("User query: ").append(query)
          .append("\n\nAvailable products:\n").append(productContext)
          .append("\n\nResearcher recommendation:\n").append(researcherFindings);

        return List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(sb.toString())
        );
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

    @SuppressWarnings("unchecked")
    private ChallengerResult parseResult(String raw) {
        try {
            String cleaned = JsonParsingUtil.stripMarkdownCodeBlock(raw);
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<>() {});

            String critique = map.get("critique") != null ? map.get("critique").toString() : raw;
            String clarificationQuestion = map.get("clarificationQuestion") != null
                    && !map.get("clarificationQuestion").toString().equalsIgnoreCase("null")
                    ? map.get("clarificationQuestion").toString()
                    : null;
            SearchRefinement refinement = parseSearchRefinement(map.get("searchRefinement"));

            return new ChallengerResult(critique, refinement, clarificationQuestion);
        } catch (Exception e) {
            log.warn("ChallengerAgent JSON parse failed, using raw text as critique. Error: {}", e.getMessage());
            return ChallengerResult.fallback(raw);
        }
    }

    @SuppressWarnings("unchecked")
    private SearchRefinement parseSearchRefinement(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) return null;

        Map<String, Object> map = (Map<String, Object>) m;
        String query = map.get("query") != null
                && !map.get("query").toString().equalsIgnoreCase("null")
                ? map.get("query").toString() : null;
        List<SortField> sortFields = parseSortFields(map.get("sortFields"));
        Map<String, Object> filterChanges = parseFilterChanges(map.get("filterChanges"));
        List<String> addKeywords = parseStringList(map.get("addKeywords"));
        List<String> removeKeywords = parseStringList(map.get("removeKeywords"));
        boolean diversifyPrices = Boolean.TRUE.equals(map.get("diversifyPrices"));

        // Return null if refinement is entirely empty (nothing useful)
        if (query == null && sortFields == null && filterChanges == null
                && addKeywords == null && removeKeywords == null && !diversifyPrices) {
            return null;
        }

        return new SearchRefinement(query, sortFields, filterChanges, addKeywords, removeKeywords, diversifyPrices);
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
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFilterChanges(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private List<String> parseStringList(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) return null;
        return list.stream()
                .filter(item -> item != null)
                .map(Object::toString)
                .toList();
    }
}
