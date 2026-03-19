package de.commerce.ai.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Weighs the researcher's recommendation against the challenger's critique and
 * delivers the final verdict as a structured {@link DeciderResult}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeciderAgent {

    private static final String SYSTEM_PROMPT = """
            You are the final decision-maker. Be decisive and brief.
            Respond in the same language as the user query (German or English).

            Output ONLY valid JSON (no markdown fences):
            {
              "verdict": "concise recommendation max 3 sentences",
              "products": ["Exact Product Name"],
              "continueSearch": false,
              "acceptRefinement": false,
              "refinedQuery": null,
              "refinedSortFields": null,
              "refinedFilterChanges": null
            }

            Rules:
            - verdict: direct conclusion, max 3 sentences
            - products: exact product names from the provided list that you recommend (1-5 items)
            - continueSearch: true ONLY if challenger's searchRefinement is genuinely useful
            - acceptRefinement: true if you accept challenger's proposed refinement
            - If continueSearch=true: set refinedQuery/refinedSortFields/refinedFilterChanges from challenger's suggestion
            - If challenger asked clarificationQuestion: set continueSearch=false (wait for user)
            - iterationHistory is provided as context to avoid circular recommendations
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    /**
     * Returns a structured result with a concise verdict, recommended product names,
     * and optional refinement directives for the next search iteration.
     */
    public DeciderResult decide(String query, String researcherFindings,
                                ChallengerResult challengerResult,
                                List<String> iterationHistory) {
        List<ChatMessage> messages = buildMessages(query, researcherFindings, challengerResult, iterationHistory);
        String raw = chatLanguageModel.generate(messages).content().text();
        log.debug("DeciderAgent raw response length: {} chars", raw.length());
        return parseResult(raw);
    }

    private List<ChatMessage> buildMessages(String query, String researcherFindings,
                                            ChallengerResult challengerResult,
                                            List<String> iterationHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("User query: ").append(query);
        sb.append("\n\nResearcher:\n").append(researcherFindings);
        sb.append("\n\nChallenger critique:\n").append(challengerResult.critique());

        if (challengerResult.hasRefinement()) {
            sb.append("\n\nChallenger refinement suggestion:\n")
              .append(challengerResult.searchRefinement().toString());
        }
        if (challengerResult.hasClarificationQuestion()) {
            sb.append("\n\nChallenger asked clarification question: ")
              .append(challengerResult.clarificationQuestion());
        }

        if (iterationHistory != null && !iterationHistory.isEmpty()) {
            sb.append("\n\nIteration history (avoid circular recommendations):\n");
            iterationHistory.forEach(h -> sb.append("- ").append(h).append('\n'));
        }

        return List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(sb.toString())
        );
    }

    @SuppressWarnings("unchecked")
    private DeciderResult parseResult(String raw) {
        String cleaned = JsonParsingUtil.stripMarkdownCodeBlock(raw);
        try {
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<>() {});
            String verdict = map.getOrDefault("verdict", "").toString();
            List<String> productNames = parseStringList(map.get("products"));
            boolean continueSearch = Boolean.TRUE.equals(map.get("continueSearch"));
            boolean acceptRefinement = Boolean.TRUE.equals(map.get("acceptRefinement"));
            String refinedQuery = map.get("refinedQuery") != null
                    && !map.get("refinedQuery").toString().equalsIgnoreCase("null")
                    ? map.get("refinedQuery").toString() : null;
            List<SortField> refinedSortFields = parseSortFields(map.get("refinedSortFields"));
            Map<String, Object> refinedFilterChanges = parseMap(map.get("refinedFilterChanges"));

            return new DeciderResult(verdict, productNames, continueSearch, acceptRefinement,
                    refinedQuery, refinedSortFields, refinedFilterChanges);
        } catch (Exception e) {
            log.warn("DeciderAgent JSON parse failed, using raw text as verdict. Error: {}", e.getMessage());
            return DeciderResult.fallback(raw);
        }
    }

    private List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(s -> s instanceof String)
                    .map(s -> (String) s)
                    .toList();
        }
        return List.of();
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
    private Map<String, Object> parseMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}
