package com.moviefinder.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CircuitBreaker<T> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final Supplier<T> fallback;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs, Supplier<T> fallback) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
        this.fallback = fallback;
    }

    public T execute(Supplier<T> action) {
        if (state.get() == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() > resetTimeoutMs) {
                state.set(State.HALF_OPEN);
                log.info("Circuit breaker [{}] transitioning to HALF_OPEN", name);
            } else {
                log.warn("Circuit breaker [{}] is OPEN, using fallback", name);
                return fallback.get();
            }
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            log.warn("Circuit breaker [{}] caught exception: {}", name, e.getMessage());
            return fallback.get();
        }
    }

    private void onSuccess() {
        failureCount.set(0);
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            log.info("Circuit breaker [{}] transitioning to CLOSED", name);
        }
    }

    private void onFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        if (failureCount.incrementAndGet() >= failureThreshold) {
            state.set(State.OPEN);
            log.warn("Circuit breaker [{}] transitioning to OPEN after {} failures", name, failureThreshold);
        }
    }

    public State getState() { return state.get(); }
    public String getName() { return name; }
    public int getFailureCount() { return failureCount.get(); }
}
