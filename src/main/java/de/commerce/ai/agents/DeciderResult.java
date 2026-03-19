package de.commerce.ai.agents;

import de.commerce.model.SortField;

import java.util.List;
import java.util.Map;

/**
 * Structured output from {@link DeciderAgent}: verdict, recommended products,
 * and optional refinement directives for the next search iteration.
 */
public record DeciderResult(
        String verdict,
        List<String> productNames,
        boolean continueSearch,
        boolean acceptRefinement,
        String refinedQuery,
        List<SortField> refinedSortFields,
        Map<String, Object> refinedFilterChanges
) {

    public static DeciderResult fallback(String rawText) {
        return new DeciderResult(rawText, List.of(), false, false, null, null, null);
    }
}
