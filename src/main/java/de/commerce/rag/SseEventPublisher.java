package de.commerce.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Thread-safe wrapper around {@link SseEmitter} that serialises payloads as JSON
 * and optionally simulates token-by-token streaming.
 */
@Slf4j
public class SseEventPublisher {

    private static final int TOKEN_STREAM_DELAY_MS = 10;

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseEventPublisher(SseEmitter emitter) {
        this.emitter = emitter;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Serialises {@code data} as JSON and sends it as an SSE event with the given name.
     * Swallows {@link IOException} (client disconnect) and logs a warning.
     */
    public synchronized void emit(String eventName, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.warn("SSE emit failed for event '{}': {}", eventName, e.getMessage());
        }
    }

    /**
     * Simulates token streaming by splitting {@code fullText} into words and sending
     * each accumulated prefix as a partial JSON chunk. The final chunk has {@code partial=false}.
     *
     * <p>Chunk schema: {@code {"queryId":"...","<fieldName>":"<text so far>","partial":<bool>}}
     */
    public synchronized void emitTokens(String eventName, String queryId,
                                        String fieldName, String fullText) {
        emitTokens(eventName, queryId, fieldName, fullText, Map.of());
    }

    /**
     * Like {@link #emitTokens} but merges {@code extraFields} into every chunk payload.
     * Useful for sending metadata like {@code iteration} alongside streamed text.
     */
    public synchronized void emitTokens(String eventName, String queryId,
                                        String fieldName, String fullText,
                                        Map<String, Object> extraFields) {
        if (fullText == null || fullText.isBlank()) {
            sendTokenChunk(eventName, queryId, fieldName, "", false, extraFields);
            return;
        }

        String[] words = fullText.split("(?<=\\s)|(?=\\s)");
        StringBuilder accumulated = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            accumulated.append(words[i]);
            boolean isLast = (i == words.length - 1);
            sendTokenChunk(eventName, queryId, fieldName,
                    isLast ? fullText : accumulated.toString(), isLast, extraFields);

            if (!isLast) {
                pauseForTokenEffect();
            }
        }
    }

    /** Completes the SSE stream normally. */
    public void complete() {
        emitter.complete();
    }

    /** Completes the SSE stream with an error. */
    public void completeWithError(Exception e) {
        emitter.completeWithError(e);
    }

    // --- private helpers ---

    private void sendTokenChunk(String eventName, String queryId,
                                String fieldName, String text, boolean isLast,
                                Map<String, Object> extraFields) {
        try {
            String chunk = buildTokenChunkJson(queryId, fieldName, text, isLast, extraFields);
            emitter.send(SseEmitter.event().name(eventName).data(chunk));
        } catch (IOException e) {
            log.warn("SSE token emit failed for event '{}': {}", eventName, e.getMessage());
        }
    }

    private String buildTokenChunkJson(String queryId, String fieldName,
                                       String text, boolean isLast,
                                       Map<String, Object> extraFields) throws JsonProcessingException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("queryId", queryId);
        node.put(fieldName, text);
        node.put("partial", !isLast);
        for (Map.Entry<String, Object> entry : extraFields.entrySet()) {
            node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }
        return objectMapper.writeValueAsString(node);
    }

    private void pauseForTokenEffect() {
        try {
            Thread.sleep(TOKEN_STREAM_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
