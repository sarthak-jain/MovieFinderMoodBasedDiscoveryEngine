package com.moviefinder.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowStep {

    public enum StepType {
        API_GATEWAY,
        CACHE_CHECK,
        CACHE_WRITE,
        GRAPH_DB_QUERY,
        EXTERNAL_API,
        RANKING,
        CIRCUIT_BREAKER,
        RATE_LIMIT,
        RESPONSE,
        ERROR
    }

    public enum CacheStatus {
        HIT, MISS, SKIP
    }

    private int stepNumber;
    private StepType type;
    private String name;
    private String detail;
    private long durationMs;
    private CacheStatus cacheStatus;
    private String query;
    private Integer resultCount;
    private Integer statusCode;
    private String errorMessage;
    private String traceId;
    private long timestamp;
    private Map<String, String> metadata;

    public WorkflowStep() {
        this.timestamp = System.currentTimeMillis();
    }

    public static WorkflowStep apiGateway(String traceId, int stepNumber, String route, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.API_GATEWAY;
        step.name = "API Gateway";
        step.detail = route;
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep cacheCheck(String traceId, int stepNumber, String key, CacheStatus status, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CACHE_CHECK;
        step.name = "Cache " + status;
        step.detail = String.format("Redis LOOKUP %s → %s", key, status);
        step.cacheStatus = status;
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Key", key);
        return step;
    }

    public static WorkflowStep graphQuery(String traceId, int stepNumber, String detail, String query, int resultCount, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.GRAPH_DB_QUERY;
        step.name = "Graph DB Query";
        step.detail = detail;
        step.query = query;
        step.resultCount = resultCount;
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Rows", String.valueOf(resultCount));
        return step;
    }

    public static WorkflowStep externalApi(String traceId, int stepNumber, String service, String detail,
                                           int cacheHits, int apiCalls, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.EXTERNAL_API;
        step.name = service + " API";
        step.detail = detail;
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Cache", cacheHits + " hits");
        step.metadata.put("API", apiCalls + " calls");
        return step;
    }

    public static WorkflowStep ranking(String traceId, int stepNumber, String formula, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.RANKING;
        step.name = "Ranking";
        step.detail = formula;
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep cacheWrite(String traceId, int stepNumber, String key, String ttl, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CACHE_WRITE;
        step.name = "Cache Write";
        step.detail = String.format("Persisting to Redis (key: %s, TTL: %s)", key, ttl);
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Key", key);
        step.metadata.put("TTL", ttl);
        return step;
    }

    public static WorkflowStep circuitBreaker(String traceId, int stepNumber, String state, int failureCount, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CIRCUIT_BREAKER;
        step.name = "Circuit Breaker";
        step.detail = String.format("State: %s (failures: %d/5)", state, failureCount);
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("CB", state);
        step.metadata.put("Failures", String.valueOf(failureCount));
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep rateLimit(String traceId, int stepNumber, int remaining, int max, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.RATE_LIMIT;
        step.name = "Rate Limiter";
        step.detail = String.format("Quota: %d/%d remaining in window", remaining, max);
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Rate", remaining + "/" + max);
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep response(String traceId, int stepNumber, int statusCode, int resultCount, long totalMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.RESPONSE;
        step.name = "Response";
        step.detail = String.format("%d OK — %d results (total: %dms)", statusCode, resultCount, totalMs);
        step.statusCode = statusCode;
        step.resultCount = resultCount;
        step.durationMs = totalMs;
        return step;
    }

    public static WorkflowStep error(String traceId, int stepNumber, String name, String errorMessage, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.ERROR;
        step.name = name;
        step.detail = errorMessage;
        step.errorMessage = errorMessage;
        step.durationMs = durationMs;
        return step;
    }

    public WorkflowStep withMeta(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new LinkedHashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }

    // Getters and setters

    public int getStepNumber() { return stepNumber; }
    public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }

    public StepType getType() { return type; }
    public void setType(StepType type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public CacheStatus getCacheStatus() { return cacheStatus; }
    public void setCacheStatus(CacheStatus cacheStatus) { this.cacheStatus = cacheStatus; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
