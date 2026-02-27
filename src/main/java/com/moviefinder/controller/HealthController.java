package com.moviefinder.controller;

import com.moviefinder.service.MetadataService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final Driver neo4jDriver;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MetadataService metadataService;

    public HealthController(Driver neo4jDriver, RedisTemplate<String, Object> redisTemplate,
                            MetadataService metadataService) {
        this.neo4jDriver = neo4jDriver;
        this.redisTemplate = redisTemplate;
        this.metadataService = metadataService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");

        // Neo4j check
        Map<String, Object> neo4jHealth = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            var result = session.run("MATCH (m:Movie) RETURN count(m) as count");
            int movieCount = result.single().get("count").asInt();
            neo4jHealth.put("status", "UP");
            neo4jHealth.put("movieCount", movieCount);
        } catch (Exception e) {
            neo4jHealth.put("status", "DOWN");
            neo4jHealth.put("error", e.getMessage());
            health.put("status", "DEGRADED");
        }
        health.put("neo4j", neo4jHealth);

        // Redis check
        Map<String, Object> redisHealth = new HashMap<>();
        try {
            redisTemplate.opsForValue().get("health-check");
            redisHealth.put("status", "UP");
        } catch (Exception e) {
            redisHealth.put("status", "DOWN");
            redisHealth.put("error", e.getMessage());
            health.put("status", "DEGRADED");
        }
        health.put("redis", redisHealth);

        // TMDb API check (circuit breaker state)
        Map<String, Object> tmdbHealth = new HashMap<>();
        tmdbHealth.put("circuitBreaker", metadataService.getCircuitBreakerState().name());
        tmdbHealth.put("rateLimitRemaining", metadataService.getRateLimiterRemaining());
        if (metadataService.getCircuitBreakerState() == com.moviefinder.resilience.CircuitBreaker.State.OPEN) {
            tmdbHealth.put("status", "DEGRADED");
        } else {
            tmdbHealth.put("status", "UP");
        }
        health.put("tmdb", tmdbHealth);

        return ResponseEntity.ok(health);
    }
}
