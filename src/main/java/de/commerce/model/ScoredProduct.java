package de.commerce.model;

/**
 * A TV product paired with its relevance score from a search query.
 */
public record ScoredProduct(
        TvProduct product,
        double score
) {
}
