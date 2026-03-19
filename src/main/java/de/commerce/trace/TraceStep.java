package de.commerce.trace;

import java.time.Instant;

/**
 * Immutable snapshot of a single pipeline step captured during a trace.
 *
 * @param name      step identifier (e.g. "router", "researcher_1")
 * @param timestamp wall-clock time when the step completed
 * @param latencyMs duration in milliseconds
 * @param data      arbitrary payload (agent output, search results, etc.)
 */
public record TraceStep(
        String name,
        Instant timestamp,
        long latencyMs,
        Object data
) {
}
