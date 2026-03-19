package de.commerce.trace;

import de.commerce.api.dto.HistoryEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable container that accumulates all tracing information for a single query lifecycle.
 * Steps are appended in execution order via {@link #addStep(TraceStep)}.
 */
public class TraceRecord {

    private String queryId;
    private String parentQueryId;
    private Instant startTime;
    private String query;
    private List<HistoryEntry> history;
    private List<TraceStep> steps;

    public TraceRecord() {
        this.steps = new ArrayList<>();
    }

    public TraceRecord(String queryId, String parentQueryId, Instant startTime,
                       String query, List<HistoryEntry> history) {
        this.queryId = queryId;
        this.parentQueryId = parentQueryId;
        this.startTime = startTime;
        this.query = query;
        this.history = history;
        this.steps = new ArrayList<>();
    }

    /** Appends a completed step to this trace. */
    public synchronized void addStep(TraceStep step) {
        steps.add(step);
    }

    // --- getters and setters ---

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public String getParentQueryId() {
        return parentQueryId;
    }

    public void setParentQueryId(String parentQueryId) {
        this.parentQueryId = parentQueryId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<HistoryEntry> getHistory() {
        return history;
    }

    public void setHistory(List<HistoryEntry> history) {
        this.history = history;
    }

    public List<TraceStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TraceStep> steps) {
        this.steps = steps;
    }
}
