package de.commerce.api.dto;

import java.util.List;

/**
 * Incoming request body for the TV-advisor endpoint.
 *
 * @param query              the user's natural-language question (required)
 * @param queryId            client-supplied ID for follow-up queries; null means a new conversation
 * @param parentQueryId      reference to the original query in a product-consultation thread; nullable
 * @param history            previous conversation turns; nullable / empty for first queries
 * @param previousProductIds product IDs of the last search results, sent back on clarify_response
 */
public record TvAdvisorRequest(
        String query,
        String queryId,
        String parentQueryId,
        List<HistoryEntry> history,
        List<String> previousProductIds
) {
}
