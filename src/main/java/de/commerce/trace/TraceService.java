package de.commerce.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.commerce.api.dto.TvAdvisorRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records pipeline execution traces and persists them as JSON files on completion.
 * Active traces are kept in memory; finished ones are written to {@code outputDir}.
 */
@Slf4j
@Service
public class TraceService {

    private final ConcurrentHashMap<String, TraceRecord> activeTraces = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final String outputDir;

    public TraceService(@Value("${trace.output-dir:data/traces}") String outputDir) {
        this.outputDir = outputDir;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Begins a new trace for the given query.
     */
    public void startTrace(String queryId, TvAdvisorRequest request) {
        List<de.commerce.api.dto.HistoryEntry> history =
                request.history() != null ? request.history() : List.of();
        TraceRecord record = new TraceRecord(
                queryId,
                request.parentQueryId(),
                Instant.now(),
                request.query(),
                history
        );
        activeTraces.put(queryId, record);
        log.debug("Trace started: queryId={}", queryId);
    }

    /**
     * Appends a step to an active trace. No-op if the trace is not found.
     */
    public void addStep(String queryId, String stepName, Object data, long latencyMs) {
        TraceRecord record = activeTraces.get(queryId);
        if (record == null) {
            log.warn("addStep called for unknown queryId='{}', step='{}'", queryId, stepName);
            return;
        }
        record.addStep(new TraceStep(stepName, Instant.now(), latencyMs, data));
    }

    /**
     * Serialises the trace to {@code {outputDir}/{queryId}.json} and removes it from memory.
     */
    public void completeTrace(String queryId) {
        TraceRecord record = activeTraces.remove(queryId);
        if (record == null) {
            log.warn("completeTrace called for unknown queryId='{}'", queryId);
            return;
        }
        persistTrace(queryId, record);
    }

    /**
     * Returns the active (in-memory) trace for the given queryId, or {@code null} if absent.
     */
    public TraceRecord getActiveTrace(String queryId) {
        return activeTraces.get(queryId);
    }

    // --- private helpers ---

    private void persistTrace(String queryId, TraceRecord record) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(queryId + ".json");
            objectMapper.writeValue(file.toFile(), record);
            log.debug("Trace persisted: {}", file);
        } catch (IOException e) {
            log.error("Failed to persist trace for queryId='{}': {}", queryId, e.getMessage(), e);
        }
    }
}
