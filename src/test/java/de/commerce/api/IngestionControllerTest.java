package de.commerce.api;

import de.commerce.elasticsearch.TvIndexService;
import de.commerce.elasticsearch.TvIndexService.BulkResult;
import de.commerce.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer tests for IngestionController.
 * Uses @WebMvcTest so only the web slice is loaded — no real Elasticsearch or CSV parsing.
 */
@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestionService ingestionService;

    @MockitoBean
    private TvIndexService tvIndexService;

    // -------------------------------------------------------------------------
    // POST /api/v1/ingestion/trigger
    // -------------------------------------------------------------------------

    @Test
    void givenSuccessfulIngestion_whenPostTrigger_thenReturns200WithStatusIndexedFailed()
            throws Exception {
        when(ingestionService.runIngestion()).thenReturn(new BulkResult(42, 0));

        mockMvc.perform(post("/api/v1/ingestion/trigger")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.indexed").value(42))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void givenSuccessfulIngestionWithCustomCsvPath_whenPostTriggerWithCsvPathParam_thenReturns200()
            throws Exception {
        String customPath = "/tmp/my-custom.csv";
        when(ingestionService.runIngestion(customPath)).thenReturn(new BulkResult(10, 1));

        mockMvc.perform(post("/api/v1/ingestion/trigger")
                        .param("csvPath", customPath)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.indexed").value(10))
                .andExpect(jsonPath("$.failed").value(1));
    }

    @Test
    void givenIngestionServiceThrowsIOException_whenPostTrigger_thenReturns500WithErrorStatus()
            throws Exception {
        when(ingestionService.runIngestion())
                .thenThrow(new IOException("CSV file not found: /data/electronics.csv"));

        mockMvc.perform(post("/api/v1/ingestion/trigger")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").exists());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/ingestion/status
    // -------------------------------------------------------------------------

    @Test
    void givenIndexHas57Documents_whenGetStatus_thenReturns200WithIndexedCountAndIndexName()
            throws Exception {
        when(tvIndexService.getCount()).thenReturn(57L);

        mockMvc.perform(get("/api/v1/ingestion/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedCount").value(57))
                .andExpect(jsonPath("$.indexName").value("tv-products"));
    }

    @Test
    void givenTvIndexServiceThrowsIOException_whenGetStatus_thenReturns500WithErrorStatus()
            throws Exception {
        when(tvIndexService.getCount())
                .thenThrow(new IOException("Elasticsearch cluster is not reachable"));

        mockMvc.perform(get("/api/v1/ingestion/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").exists());
    }
}
