package de.commerce.api;

import de.commerce.elasticsearch.TvIndexService;
import de.commerce.elasticsearch.TvIndexService.BulkResult;
import de.commerce.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * REST API for triggering the CSV ingestion pipeline and querying index status.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;
    private final TvIndexService tvIndexService;

    /**
     * POST /api/v1/ingestion/trigger
     * Starts ingestion from the configured CSV path (or a custom path via query param).
     *
     * @param csvPath optional override for the CSV file path
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger(
            @RequestParam(name = "csvPath", required = false) String csvPath) {

        log.info("Ingestion trigger received. csvPath override='{}'", csvPath);

        try {
            BulkResult result = (csvPath != null && !csvPath.isBlank())
                    ? ingestionService.runIngestion(csvPath)
                    : ingestionService.runIngestion();

            Map<String, Object> body = Map.of(
                    "status", "completed",
                    "indexed", result.indexed(),
                    "failed", result.failed()
            );
            return ResponseEntity.ok(body);

        } catch (IOException e) {
            log.error("Ingestion failed with IOException: {}", e.getMessage(), e);
            Map<String, Object> error = Map.of(
                    "status", "error",
                    "message", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * GET /api/v1/ingestion/status
     * Returns the current document count in the tv-products index.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            long count = tvIndexService.getCount();
            Map<String, Object> body = Map.of(
                    "indexedCount", count,
                    "indexName", TvIndexService.INDEX_NAME
            );
            return ResponseEntity.ok(body);

        } catch (IOException e) {
            log.error("Could not retrieve index status: {}", e.getMessage(), e);
            Map<String, Object> error = Map.of(
                    "status", "error",
                    "message", e.getMessage()
            );
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
