package de.commerce.ai.agents;

/**
 * Decision returned by the {@link RouterAgent}.
 *
 * <ul>
 *   <li>{@code "new_search"}            — user wants to search for TVs or accessories</li>
 *   <li>{@code "clarify_response"}      — last assistant message was a clarification question; this is the user's answer</li>
 *   <li>{@code "inspiration_response"}  — user responds to an inspiration/diversity offer</li>
 *   <li>{@code "decline"}               — completely off-topic; message contains a polite refusal</li>
 *   <li>{@code "proceed"}               — legacy alias for "new_search" (backwards-compat)</li>
 * </ul>
 */
public record RouterDecision(
        String action,
        String message,
        String question,
        boolean inspirationNeeded
) {
}
