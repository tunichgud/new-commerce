package de.commerce.api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Skeleton acceptance tests for the TvAdvisor SSE/RAG endpoint.
 * All tests are @Disabled — they document the required behaviour for
 * Phase 0 BM25 search and the multi-agent RAG pipeline.
 *
 * Activate these tests once the following components exist:
 *   - POST /api/v1/tv-advisor/query (SSE, text/event-stream)
 *   - Phase 0 BM25 search (ElasticsearchService.searchBm25)
 *   - Researcher / Challenger / Decider agent loop
 *
 * AT-B001: SSE endpoint exists with correct Content-Type
 * AT-B002: Phase 0 BM25 returns phase_0_results SSE event
 * AT-B003: Researcher event emitted after Phase 0
 * AT-B004: final_answer event is the last non-complete event
 * AT-B005: stream_complete is the terminal SSE event
 * AT-B006: invalid request body returns 400
 */
@Disabled("AT-B: TvAdvisorController not yet implemented")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TvAdvisorControllerSkeletonTest {

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // AT-B001: SSE endpoint responds with text/event-stream
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B001: POST /api/v1/tv-advisor/query not yet implemented")
    void givenValidQuery_whenPostQuery_thenResponseContentTypeIsTextEventStream() throws Exception {
        mockMvc.perform(post("/api/v1/tv-advisor/query")
                        .contentType("application/json")
                        .content("{\"query\": \"Ich suche einen 55 Zoll OLED Fernseher\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
    }

    // -------------------------------------------------------------------------
    // AT-B002: Phase 0 BM25 event is emitted
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B002: Phase 0 BM25 search (ElasticsearchService) not yet implemented")
    void givenValidQuery_whenPostQuery_thenFirstSseEventIsPhase0Results() throws Exception {
        // Expect an SSE event: event: phase_0_results
        // data: { "products": [...] }
        fail("AT-B002: Implement when ElasticsearchService.searchBm25() exists." +
             " Assert that the SSE stream contains 'event:phase_0_results' before any RAG events.");
    }

    // -------------------------------------------------------------------------
    // AT-B003: Researcher event follows Phase 0
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B003: Researcher agent not yet implemented")
    void givenValidQuery_whenPostQuery_thenResearcherFindingsEventIsEmitted() throws Exception {
        // Expect: event: researcher_findings
        // data: { "findings": "..." }
        fail("AT-B003: Implement when ResearcherAgent exists." +
             " Assert that 'event:researcher_findings' appears after 'event:phase_0_results'.");
    }

    // -------------------------------------------------------------------------
    // AT-B004: final_answer event contains the agent's recommendation
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B004: DeciderAgent not yet implemented")
    void givenValidQuery_whenPostQuery_thenFinalAnswerEventContainsRecommendationText()
            throws Exception {
        // Expect: event: final_answer
        // data: { "answer": "<non-empty string>" }
        fail("AT-B004: Implement when DeciderAgent exists." +
             " Assert that 'event:final_answer' data.answer is non-blank.");
    }

    // -------------------------------------------------------------------------
    // AT-B005: stream_complete is the terminal event
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B005: SSE orchestration not yet implemented")
    void givenValidQuery_whenPostQuery_thenLastSseEventIsStreamComplete() throws Exception {
        // Expect: event: stream_complete as the final event in the stream
        fail("AT-B005: Implement when SSE orchestrator exists." +
             " Assert that 'event:stream_complete' is the last event in the SSE stream.");
    }

    // -------------------------------------------------------------------------
    // AT-B006: Missing query body returns 400
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B006: TvAdvisorController not yet implemented")
    void givenMissingQueryBody_whenPostQuery_thenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/tv-advisor/query")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
