package com.moviefinder.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final String name;
    private final int maxRequests;
    private final long windowMs;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    public RateLimiter(String name, int maxRequests, long windowMs) {
        this.name = name;
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() > windowMs) {
            windowStart.set(now);
            requestCount.set(0);
        }

        if (requestCount.get() < maxRequests) {
            requestCount.incrementAndGet();
            return true;
        }

        log.warn("Rate limiter [{}] exceeded: {}/{} in window", name, requestCount.get(), maxRequests);
        return false;
    }

    public int getRemainingQuota() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() > windowMs) {
            return maxRequests;
        }
        return Math.max(0, maxRequests - requestCount.get());
    }

    public String getName() { return name; }
    public int getMaxRequests() { return maxRequests; }
}
