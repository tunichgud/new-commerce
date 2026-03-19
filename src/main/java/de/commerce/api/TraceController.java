package de.commerce.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.commerce.trace.TraceRecord;
import de.commerce.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * REST endpoint for retrieving persisted and active pipeline traces.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tv-advisor")
public class TraceController {

    private static final String TRACE_OUTPUT_DIR = "data/traces";

    private final TraceService traceService;
    private final ObjectMapper objectMapper;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * GET /api/v1/tv-advisor/trace/{queryId}
     * Returns the trace JSON for the given queryId.
     * Falls back to the in-memory active trace if the file does not yet exist.
     * Returns 404 if neither source contains the trace.
     *
     * @param queryId the pipeline trace identifier
     */
    @GetMapping("/trace/{queryId}")
    public ResponseEntity<String> getTrace(@PathVariable String queryId) {
        Path traceFile = Path.of(TRACE_OUTPUT_DIR, queryId + ".json");

        if (Files.exists(traceFile)) {
            return readPersistedTrace(traceFile, queryId);
        }

        return readActiveTrace(queryId);
    }

    // --- private helpers ---

    private ResponseEntity<String> readPersistedTrace(Path traceFile, String queryId) {
        try {
            String json = Files.readString(traceFile);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(json);
        } catch (IOException e) {
            log.error("Failed to read trace file for queryId='{}': {}", queryId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private ResponseEntity<String> readActiveTrace(String queryId) {
        TraceRecord active = traceService.getActiveTrace(queryId);
        if (active == null) {
            log.debug("Trace not found for queryId='{}'", queryId);
            return ResponseEntity.notFound().build();
        }

        try {
            String json = objectMapper.writeValueAsString(active);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(json);
        } catch (IOException e) {
            log.error("Failed to serialise active trace for queryId='{}': {}",
                    queryId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
