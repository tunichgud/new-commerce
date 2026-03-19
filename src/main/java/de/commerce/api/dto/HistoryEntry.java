package de.commerce.api.dto;

/**
 * Represents a single turn in the conversation history.
 * role is typically "user" or "assistant".
 */
public record HistoryEntry(String role, String content) {
}
