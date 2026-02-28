package com.moviefinder.workflow;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WorkflowTracer {

    private final WorkflowEmitter emitter;

    public WorkflowTracer(WorkflowEmitter emitter) {
        this.emitter = emitter;
    }

    public Trace startTrace(String method, String path) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        emitter.broadcastRequestStart(traceId, method, path);
        return new Trace(traceId, emitter);
    }

    public static class Trace {
        private final String traceId;
        private final WorkflowEmitter emitter;
        private final AtomicInteger stepCounter = new AtomicInteger(0);
        private final long startTime = System.nanoTime();

        Trace(String traceId, WorkflowEmitter emitter) {
            this.traceId = traceId;
            this.emitter = emitter;
        }

        public String getTraceId() { return traceId; }

        public int nextStep() {
            return stepCounter.incrementAndGet();
        }

        public long elapsedMs() {
            return (System.nanoTime() - startTime) / 1_000_000;
        }

        public void emit(WorkflowStep step) {
            emitter.broadcast(step);
        }

        public void emitApiGateway(String route) {
            long start = System.nanoTime();
            long duration = (System.nanoTime() - start) / 1_000_000 + 1;
            emit(WorkflowStep.apiGateway(traceId, nextStep(), route, duration));
        }

        public void emitAiQueryParser(String input, String parsedOutput, long durationMs) {
            emit(WorkflowStep.aiQueryParser(traceId, nextStep(), input, parsedOutput, durationMs));
        }

        public void emitCacheCheck(String key, WorkflowStep.CacheStatus status, long durationMs) {
            emit(WorkflowStep.cacheCheck(traceId, nextStep(), key, status, durationMs));
        }

        public void emitGraphQuery(String detail, String query, int resultCount, long durationMs) {
            emit(WorkflowStep.graphQuery(traceId, nextStep(), detail, query, resultCount, durationMs));
        }

        public void emitExternalApi(String service, String detail, int cacheHits, int apiCalls, long durationMs) {
            emit(WorkflowStep.externalApi(traceId, nextStep(), service, detail, cacheHits, apiCalls, durationMs));
        }

        public void emitRanking(String formula, long durationMs) {
            emit(WorkflowStep.ranking(traceId, nextStep(), formula, durationMs));
        }

        public void emitCacheWrite(String key, String ttl, long durationMs) {
            emit(WorkflowStep.cacheWrite(traceId, nextStep(), key, ttl, durationMs));
        }

        public void emitResponse(int statusCode, int resultCount) {
            emit(WorkflowStep.response(traceId, nextStep(), statusCode, resultCount, elapsedMs()));
        }

        public void emitCircuitBreaker(String state, int failureCount) {
            emit(WorkflowStep.circuitBreaker(traceId, nextStep(), state, failureCount, 0));
        }

        public void emitRateLimit(int remaining, int max) {
            emit(WorkflowStep.rateLimit(traceId, nextStep(), remaining, max, 0));
        }

        public void emitError(String name, String errorMessage, long durationMs) {
            emit(WorkflowStep.error(traceId, nextStep(), name, errorMessage, durationMs));
        }
    }
}
