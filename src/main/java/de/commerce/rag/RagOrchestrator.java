package de.commerce.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import de.commerce.ai.agents.ChallengerAgent;
import de.commerce.ai.agents.ChallengerResult;
import de.commerce.ai.agents.DeciderAgent;
import de.commerce.ai.agents.DeciderResult;
import de.commerce.ai.agents.FilterExtractorAgent;
import de.commerce.ai.agents.ResearcherAgent;
import de.commerce.ai.agents.RouterAgent;
import de.commerce.ai.agents.RouterDecision;
import de.commerce.api.dto.HistoryEntry;
import de.commerce.api.dto.TvAdvisorRequest;
import de.commerce.elasticsearch.DiversitySearchService;
import de.commerce.elasticsearch.ElasticsearchSearchService;
import de.commerce.elasticsearch.TvIndexService;
import de.commerce.model.ScoredProduct;
import de.commerce.model.SearchFilters;
import de.commerce.model.TvProduct;
import de.commerce.trace.TraceService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the complete TV-advisor RAG pipeline:
 * routing → filter extraction → hybrid search → multi-agent loop → streaming response.
 * All progress is streamed to the client via {@link SseEventPublisher}.
 */
@Slf4j
@Service
public class RagOrchestrator {

    private static final String ACTION_DECLINE = "decline";
    private static final String ACTION_CLARIFY_RESPONSE = "clarify_response";
    private static final String ACTION_INSPIRATION_RESPONSE = "inspiration_response";

    private final RouterAgent routerAgent;
    private final FilterExtractorAgent filterExtractorAgent;
    private final ResearcherAgent researcherAgent;
    private final ChallengerAgent challengerAgent;
    private final DeciderAgent deciderAgent;
    private final ElasticsearchSearchService searchService;
    private final DiversitySearchService diversitySearchService;
    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;
    private final TraceService traceService;
    private final int maxIterations;
    private final int topK;

    public RagOrchestrator(
            RouterAgent routerAgent,
            FilterExtractorAgent filterExtractorAgent,
            ResearcherAgent researcherAgent,
            ChallengerAgent challengerAgent,
            DeciderAgent deciderAgent,
            ElasticsearchSearchService searchService,
            DiversitySearchService diversitySearchService,
            ElasticsearchClient esClient,
            EmbeddingModel embeddingModel,
            TraceService traceService,
            @Value("${rag.max-iterations:3}") int maxIterations,
            @Value("${search.phase0.top-k:10}") int topK) {
        this.routerAgent = routerAgent;
        this.filterExtractorAgent = filterExtractorAgent;
        this.researcherAgent = researcherAgent;
        this.challengerAgent = challengerAgent;
        this.deciderAgent = deciderAgent;
        this.searchService = searchService;
        this.diversitySearchService = diversitySearchService;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.traceService = traceService;
        this.maxIterations = maxIterations;
        this.topK = topK;
    }

    /**
     * Runs the full RAG pipeline for the given request and publishes SSE events
     * to the client through {@code publisher}. Completes the emitter when done.
     */
    public void orchestrate(TvAdvisorRequest request, SseEventPublisher publisher) {
        String queryId = resolveQueryId(request);
        traceService.startTrace(queryId, request);

        try {
            publisher.emit("query_accepted", Map.of("queryId", queryId));

            List<HistoryEntry> history = request.history() != null ? request.history() : List.of();

            RouterDecision routerDecision = runRouter(queryId, request, history, publisher);
            if (routerDecision == null) {
                return;
            }

            List<ScoredProduct> currentResults;
            String currentQuery = request.query();
            SearchFilters currentFilters;

            String action = routerDecision.action();

            if (ACTION_CLARIFY_RESPONSE.equalsIgnoreCase(action)) {
                // Combine ALL user turns from history with the current answer — preserves accumulated context
                String historyContext = buildCombinedQueryContext(history);
                if (historyContext != null && !historyContext.equals(currentQuery)) {
                    currentQuery = historyContext + ". " + currentQuery;
                }
                currentFilters = runFilterExtractor(queryId, currentQuery);
                float[] embedding = generateEmbedding(currentQuery);
                currentResults = executeSearch(queryId, currentQuery, embedding, currentFilters);
            } else if (ACTION_INSPIRATION_RESPONSE.equalsIgnoreCase(action)) {
                currentResults = diversitySearchService.searchDiverse();
                currentFilters = new SearchFilters(null, null, null, null, null, null);
                emitPhaseResults(queryId, 0, currentQuery, currentResults, null, publisher);
                publisher.emit("stream_complete", Map.of("queryId", queryId));
                traceService.completeTrace(queryId);
                return;
            } else {
                // new_search or proceed (backwards compat)
                if (routerDecision.inspirationNeeded()) {
                    publisher.emit("inspire_question", Map.of(
                            "queryId", queryId,
                            "message", buildInspireMessage(currentQuery)
                    ));
                    publisher.emit("stream_complete", Map.of("queryId", queryId));
                    traceService.completeTrace(queryId);
                    return;
                }

                currentFilters = runFilterExtractor(queryId, currentQuery);
                float[] embedding = generateEmbedding(currentQuery);
                currentResults = executeSearch(queryId, currentQuery, embedding, currentFilters);
            }

            // Agent loop
            // Clarification is only allowed on the first search; on clarify_response the user
            // already answered — the Challenger must not open another question.
            boolean clarificationAllowed = !ACTION_CLARIFY_RESPONSE.equalsIgnoreCase(action);

            List<String> iterationHistory = new ArrayList<>();
            List<ScoredProduct> previousPhaseResults = null;

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                int phase = iteration - 1;
                emitPhaseResults(queryId, phase, currentQuery, currentResults, previousPhaseResults, publisher);

                List<TvProduct> products = currentResults.stream()
                        .map(ScoredProduct::product)
                        .toList();

                String researcherOutput = runResearcher(queryId, currentQuery, products, history, iteration, publisher);
                iterationHistory.add("Iteration " + iteration + " - Researcher: " + researcherOutput);

                ChallengerResult challengerResult = runChallenger(queryId, currentQuery, products,
                        researcherOutput, history, iteration, publisher);
                iterationHistory.add("Iteration " + iteration + " - Challenger: " + challengerResult.critique());

                if (clarificationAllowed && challengerResult.hasClarificationQuestion()) {
                    List<String> productIds = products.stream()
                            .map(TvProduct::productId)
                            .filter(id -> id != null && !id.isBlank())
                            .toList();
                    publisher.emit("pending_clarification", Map.of(
                            "queryId", queryId,
                            "question", challengerResult.clarificationQuestion(),
                            "previousProductIds", productIds
                    ));
                    publisher.emit("stream_complete", Map.of("queryId", queryId));
                    traceService.addStep(queryId, "pending_clarification",
                            challengerResult.clarificationQuestion(), 0);
                    traceService.completeTrace(queryId);
                    return;
                }

                DeciderResult deciderResult = runDecider(queryId, currentQuery, products,
                        researcherOutput, challengerResult, iterationHistory, iteration, publisher);

                List<TvProduct> refined = resolveRecommendedProducts(products, deciderResult.productNames());
                emitRefinedProducts(queryId, iteration, refined, publisher);

                iterationHistory.add("Iteration " + iteration + " - Decider: " + deciderResult.verdict());

                // Inspiration via Challenger + Decider
                if (challengerResult.hasRefinement()
                        && challengerResult.searchRefinement().diversifyPrices()
                        && deciderResult.acceptRefinement()) {
                    publisher.emit("inspire_question", Map.of(
                            "queryId", queryId,
                            "message", buildInspireMessage(currentQuery)
                    ));
                    publisher.emit("stream_complete", Map.of("queryId", queryId));
                    traceService.completeTrace(queryId);
                    return;
                }

                if (!deciderResult.continueSearch() || iteration == maxIterations) {
                    log.debug("Agent loop ending at iteration={} for queryId='{}'", iteration, queryId);
                    break;
                }

                // Prepare next iteration
                if (deciderResult.acceptRefinement() && deciderResult.refinedQuery() != null) {
                    currentQuery = deciderResult.refinedQuery();
                }
                SearchFilters newFilters = currentFilters;
                if (deciderResult.refinedFilterChanges() != null) {
                    newFilters = SearchFilters.merge(currentFilters, deciderResult.refinedFilterChanges());
                }
                if (deciderResult.refinedSortFields() != null) {
                    newFilters = new SearchFilters(
                            newFilters.brand(), newFilters.screenSizeInch(), newFilters.panelType(),
                            newFilters.maxPriceEur(), newFilters.minRating(),
                            deciderResult.refinedSortFields()
                    );
                }
                currentFilters = newFilters;
                previousPhaseResults = currentResults;
                float[] newEmbedding = generateEmbedding(currentQuery);
                currentResults = executeSearch(queryId, currentQuery, newEmbedding, currentFilters);
            }

            publisher.emit("stream_complete", Map.of("queryId", queryId));
            traceService.completeTrace(queryId);

        } catch (Exception e) {
            log.error("Pipeline error for queryId='{}': {}", queryId, e.getMessage(), e);
            publisher.emit("error", Map.of("queryId", queryId, "message", buildFriendlyErrorMessage(e)));
            publisher.emit("stream_complete", Map.of("queryId", queryId));
            traceService.addStep(queryId, "error", e.getMessage(), 0);
            traceService.completeTrace(queryId);
        } finally {
            publisher.complete();
        }
    }

    // --- pipeline steps ---

    private String resolveQueryId(TvAdvisorRequest request) {
        return (request.queryId() != null && !request.queryId().isBlank())
                ? request.queryId()
                : UUID.randomUUID().toString();
    }

    private RouterDecision runRouter(String queryId, TvAdvisorRequest request,
                                     List<HistoryEntry> history, SseEventPublisher publisher) {
        long start = System.nanoTime();
        RouterDecision decision = routerAgent.route(request.query(), history);
        long latencyMs = elapsedMs(start);

        traceService.addStep(queryId, "router", decision, latencyMs);
        publisher.emit("router_decision",
                Map.of("queryId", queryId, "action", decision.action(),
                        "message", decision.message() != null ? decision.message() : ""));

        if (ACTION_DECLINE.equalsIgnoreCase(decision.action())) {
            publisher.emit("stream_complete", Map.of("queryId", queryId));
            traceService.completeTrace(queryId);
            publisher.complete();
            return null;
        }
        return decision;
    }

    private SearchFilters runFilterExtractor(String queryId, String query) {
        long start = System.nanoTime();
        SearchFilters filters = filterExtractorAgent.extract(query);
        long latencyMs = elapsedMs(start);
        traceService.addStep(queryId, "filter_extraction", filters, latencyMs);
        return filters;
    }

    private float[] generateEmbedding(String query) {
        return embeddingModel.embed(query).content().vector();
    }

    private List<ScoredProduct> executeSearch(String queryId, String query,
                                              float[] embedding, SearchFilters filters) throws IOException {
        long start = System.nanoTime();
        List<ScoredProduct> results = searchService.searchHybrid(query, embedding, filters, topK);
        long latencyMs = elapsedMs(start);

        traceService.addStep(queryId, "search",
                Map.of("query", query, "topK", topK, "filtersApplied", filters,
                        "resultCount", results.size()), latencyMs);
        return results;
    }

    /**
     * Emits a {@code phase_results} event. Products new compared to the previous phase
     * are marked with {@code isNew=true}.
     */
    private void emitPhaseResults(String queryId, int phase, String query,
                                  List<ScoredProduct> results,
                                  List<ScoredProduct> previousResults, SseEventPublisher publisher) {
        Set<String> previousIds = previousResults == null ? Set.of()
                : previousResults.stream()
                        .map(sp -> sp.product().productId())
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());

        List<Map<String, Object>> productPayload = results.stream()
                .map(sp -> {
                    TvProduct p = sp.product();
                    boolean isNew = p.productId() == null || !previousIds.contains(p.productId());
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("productId", p.productId() != null ? p.productId() : "");
                    entry.put("name", p.name() != null ? p.name() : "");
                    entry.put("brand", p.brand() != null ? p.brand() : "");
                    entry.put("priceEur", p.priceEur() != null ? p.priceEur() : 0.0);
                    entry.put("panelType", p.panelType() != null ? p.panelType() : "");
                    entry.put("screenSizeInch", p.screenSizeInch() != null ? p.screenSizeInch() : 0);
                    entry.put("resolution", p.resolution() != null ? p.resolution() : "");
                    entry.put("ratings", p.ratings() != null ? p.ratings() : 0.0);
                    entry.put("imageUrl", p.imageUrl() != null ? p.imageUrl() : "");
                    entry.put("productUrl", p.productUrl() != null ? p.productUrl() : "");
                    entry.put("score", sp.score());
                    entry.put("isNew", isNew);
                    return entry;
                })
                .toList();

        publisher.emit("phase_results", Map.of(
                "queryId", queryId,
                "phase", phase,
                "query", query != null ? query : "",
                "products", productPayload
        ));
    }

    private void emitRefinedProducts(String queryId, int iteration, List<TvProduct> refined,
                                     SseEventPublisher publisher) {
        List<Map<String, Object>> productPayload = refined.stream()
                .map(p -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("productId", p.productId() != null ? p.productId() : "");
                    entry.put("name", p.name() != null ? p.name() : "");
                    entry.put("brand", p.brand() != null ? p.brand() : "");
                    entry.put("priceEur", p.priceEur() != null ? p.priceEur() : 0.0);
                    entry.put("panelType", p.panelType() != null ? p.panelType() : "");
                    entry.put("screenSizeInch", p.screenSizeInch() != null ? p.screenSizeInch() : 0);
                    entry.put("resolution", p.resolution() != null ? p.resolution() : "");
                    entry.put("ratings", p.ratings() != null ? p.ratings() : 0.0);
                    entry.put("imageUrl", p.imageUrl() != null ? p.imageUrl() : "");
                    entry.put("productUrl", p.productUrl() != null ? p.productUrl() : "");
                    return entry;
                })
                .toList();
        publisher.emit("products_refined",
                Map.of("queryId", queryId, "iteration", iteration, "products", productPayload));
    }

    private String runResearcher(String queryId, String query, List<TvProduct> products,
                                 List<HistoryEntry> history, int iteration,
                                 SseEventPublisher publisher) {
        long start = System.nanoTime();
        String output = researcherAgent.research(query, products, history);
        long latencyMs = elapsedMs(start);
        traceService.addStep(queryId, "researcher_" + iteration, output, latencyMs);
        publisher.emitTokens("researcher_findings", queryId, "findings", output,
                Map.of("iteration", iteration));
        return output;
    }

    private ChallengerResult runChallenger(String queryId, String query, List<TvProduct> products,
                                           String researcherOutput, List<HistoryEntry> history,
                                           int iteration, SseEventPublisher publisher) {
        long start = System.nanoTime();
        ChallengerResult result = challengerAgent.challenge(query, products, researcherOutput, history);
        long latencyMs = elapsedMs(start);
        traceService.addStep(queryId, "challenger_" + iteration, result, latencyMs);
        publisher.emitTokens("challenger_feedback", queryId, "feedback", result.critique(),
                Map.of("iteration", iteration));
        return result;
    }

    private DeciderResult runDecider(String queryId, String query, List<TvProduct> products,
                                     String researcherOutput, ChallengerResult challengerResult,
                                     List<String> iterationHistory, int iteration,
                                     SseEventPublisher publisher) {
        long start = System.nanoTime();
        DeciderResult result = deciderAgent.decide(query, researcherOutput, challengerResult, iterationHistory);
        long latencyMs = elapsedMs(start);
        traceService.addStep(queryId, "decider_" + iteration, result, latencyMs);
        publisher.emitTokens("decider_verdict", queryId, "verdict", result.verdict(),
                Map.of("iteration", iteration));
        return result;
    }

    /**
     * Loads products by ID using Elasticsearch mget. Returns empty list if ids is null/empty.
     * Products are returned with score=0.
     */
    @SuppressWarnings("unchecked")
    private List<ScoredProduct> loadPreviousProducts(List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) {
            log.debug("loadPreviousProducts: no previousProductIds provided.");
            return List.of();
        }

        MgetRequest mgetRequest = MgetRequest.of(r -> r
                .index(TvIndexService.INDEX_NAME)
                .ids(ids)
        );

        MgetResponse<Map> response = esClient.mget(mgetRequest, Map.class);
        List<ScoredProduct> results = new ArrayList<>();

        for (MultiGetResponseItem<Map> item : response.docs()) {
            if (item.isResult() && item.result().found()) {
                Map<String, Object> source = item.result().source();
                if (source != null) {
                    TvProduct product = searchService.mapToTvProduct(source, item.result().id());
                    results.add(new ScoredProduct(product, 0.0));
                }
            }
        }
        log.debug("loadPreviousProducts: loaded {} of {} requested products.", results.size(), ids.size());
        return results;
    }

    private static final int MAX_REFINED_PICKS = 5;

    /**
     * Returns the Decider's recommended products in recommendation order, max {@value MAX_REFINED_PICKS}.
     * Matches by longest name overlap (one candidate per recommended name to avoid duplicates).
     * Falls back to the top {@value MAX_REFINED_PICKS} candidates when no matches are found.
     */
    private List<TvProduct> resolveRecommendedProducts(List<TvProduct> candidates,
                                                        List<String> recommendedNames) {
        if (recommendedNames != null && !recommendedNames.isEmpty()) {
            List<TvProduct> matched = new ArrayList<>();
            for (String rn : recommendedNames) {
                String rnLower = rn.toLowerCase();
                candidates.stream()
                        .filter(p -> p.name() != null && !matched.contains(p))
                        .filter(p -> {
                            String nameLower = p.name().toLowerCase();
                            // prefer longer overlap: both must contain at least 4 chars of each other
                            return nameLower.contains(rnLower) || rnLower.contains(nameLower);
                        })
                        .findFirst()
                        .ifPresent(matched::add);
                if (matched.size() >= MAX_REFINED_PICKS) break;
            }
            if (!matched.isEmpty()) return matched;
        }
        // Fallback: top candidates from search ranking
        return candidates.stream().limit(MAX_REFINED_PICKS).toList();
    }

    /**
     * Combines ALL user turns from history into a single context string so that
     * accumulated clarification answers (budget, size, etc.) are not lost across rounds.
     */
    private String buildCombinedQueryContext(List<HistoryEntry> history) {
        if (history == null || history.isEmpty()) return null;
        String combined = history.stream()
                .filter(e -> "user".equalsIgnoreCase(e.role()))
                .map(HistoryEntry::content)
                .collect(java.util.stream.Collectors.joining(". "));
        return combined.isBlank() ? null : combined;
    }

    private String buildInspireMessage(String query) {
        return "Deine Anfrage ist noch recht offen. Soll ich dir eine bunte Auswahl an Fernsehern "
                + "aus verschiedenen Preisklassen zeigen, damit du einen Überblick bekommst? "
                + "(Preiswert · Mittelklasse · Premium)";
    }

    private String buildFriendlyErrorMessage(Exception e) {
        return "An error occurred while processing your request. Please try again later.";
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
