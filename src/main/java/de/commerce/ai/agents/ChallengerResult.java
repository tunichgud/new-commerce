package de.commerce.ai.agents;

/**
 * Structured output from {@link ChallengerAgent}: a concise critique, an optional
 * structured search refinement proposal, and an optional clarification question.
 */
public record ChallengerResult(
        String critique,
        SearchRefinement searchRefinement, // null when no refinement is proposed
        String clarificationQuestion       // null when no question is needed
) {

    public static ChallengerResult fallback(String raw) {
        return new ChallengerResult(raw, null, null);
    }

    public boolean hasClarificationQuestion() {
        return clarificationQuestion != null && !clarificationQuestion.isBlank();
    }

    public boolean hasRefinement() {
        return searchRefinement != null;
    }
}
