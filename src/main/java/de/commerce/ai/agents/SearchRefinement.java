package de.commerce.ai.agents;

import de.commerce.model.SortField;

import java.util.List;
import java.util.Map;

/**
 * Represents a structured search refinement proposed by the {@link ChallengerAgent}.
 * All fields are nullable — only set fields represent actual proposed changes.
 */
public record SearchRefinement(
        String query,                     // new search query or null
        List<SortField> sortFields,       // desired sort order or null
        Map<String, Object> filterChanges,// filter delta (key → new value; null value = remove) or null
        List<String> addKeywords,         // keywords to add to query expansion or null
        List<String> removeKeywords,      // keywords to remove from query or null
        boolean diversifyPrices           // true → challenger recommends inspiration/diversity mode
) {
}
