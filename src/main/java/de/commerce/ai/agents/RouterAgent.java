package de.commerce.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.commerce.api.dto.HistoryEntry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Routes incoming user queries: decides whether to proceed with a new search,
 * handle a clarification response, handle an inspiration response, or decline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterAgent {

    private static final String SYSTEM_PROMPT = """
            You are a routing agent for a TV advisor system. Classify the user's input into exactly one of these actions.

            Context: The conversation history shows the current state. Check the last assistant message to understand context.

            Actions:
            - "new_search": User wants to search for TVs or TV accessories. Use this for any new product search.
            - "clarify_response": The last assistant message was a clarification question. This is the user's answer to it.
            - "inspiration_response": The last assistant message asked if the user wants to see diverse product suggestions. User is responding (yes/no/maybe).
            - "decline": Completely off-topic (not TVs, not related electronics accessories).

            Inspiration trigger: Set inspirationNeeded=true ONLY when the query is completely empty of any useful signal — no use case, no device, no room, no preference, no audience, no activity. Examples that ARE concrete enough (do NOT set inspirationNeeded):
            - Gaming console mentioned (PS4, Xbox, Switch) → implies HDMI, max FullHD, refresh rate matters
            - Room type (bedroom, living room, kids room) → implies size range
            - Activity (movies, sports, gaming) → implies panel/refresh requirements
            - Audience (kids, elderly) → implies price/simplicity expectations
            - Any brand, size, or price hint
            Only set inspirationNeeded=true for truly empty queries like "I want a TV" or "Zeig mir Fernseher" with zero further context.

            Output ONLY valid JSON (no markdown):
            {"action":"new_search","message":"","question":null,"inspirationNeeded":false}

            Rules:
            - question: only set if you need to ask something before routing (rare, use sparingly)
            - inspirationNeeded: true only when query is too vague to return useful results
            - Respond in the same language as the user (German or English)
            """;

    private final dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    /**
     * Routes the given query and returns a {@link RouterDecision}.
     * Falls back to action "new_search" if the LLM response cannot be parsed.
     */
    public RouterDecision route(String query, List<HistoryEntry> history) {
        List<ChatMessage> messages = buildMessages(query, history);
        String raw = chatLanguageModel.generate(messages).content().text();
        log.debug("RouterAgent raw response: {}", raw);
        return parseDecision(raw);
    }

    private List<ChatMessage> buildMessages(String query, List<HistoryEntry> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        if (history != null) {
            for (HistoryEntry entry : history) {
                if ("user".equalsIgnoreCase(entry.role())) {
                    messages.add(UserMessage.from(entry.content()));
                } else if ("assistant".equalsIgnoreCase(entry.role())) {
                    messages.add(AiMessage.from(entry.content()));
                }
            }
        }
        messages.add(UserMessage.from(query));
        return messages;
    }

    private RouterDecision parseDecision(String raw) {
        try {
            String cleaned = JsonParsingUtil.stripMarkdownCodeBlock(raw);
            Map<?, ?> map = objectMapper.readValue(cleaned, Map.class);
            String action = map.get("action") != null ? map.get("action").toString() : "new_search";
            String message = map.get("message") != null ? map.get("message").toString() : "";
            String question = map.get("question") != null ? map.get("question").toString() : null;
            boolean inspirationNeeded = Boolean.TRUE.equals(map.get("inspirationNeeded"));
            return new RouterDecision(action, message, question, inspirationNeeded);
        } catch (Exception e) {
            log.warn("RouterAgent could not parse LLM response, defaulting to new_search. Response: {}", raw, e);
            return new RouterDecision("new_search", "", null, false);
        }
    }
}
