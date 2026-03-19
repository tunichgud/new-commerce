package de.commerce.api;

import de.commerce.api.dto.TvAdvisorRequest;
import de.commerce.rag.RagOrchestrator;
import de.commerce.rag.SseEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;

/**
 * REST endpoint for the TV-advisor feature.
 * Accepts a JSON query and returns a Server-Sent Events stream of pipeline progress.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tv-advisor")
@RequiredArgsConstructor
public class TvAdvisorController {

    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private final RagOrchestrator orchestrator;

    /**
     * POST /api/v1/tv-advisor/query
     * Starts the RAG pipeline in a virtual thread and immediately returns the SSE emitter.
     *
     * @param request the user's query with optional history and IDs
     * @return SSE stream of pipeline events
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@RequestBody TvAdvisorRequest request) {
        log.info("TV-advisor query received: query='{}'", request.query());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        SseEventPublisher publisher = new SseEventPublisher(emitter);

        Thread.startVirtualThread(() -> orchestrator.orchestrate(request, publisher));

        return emitter;
    }
}
