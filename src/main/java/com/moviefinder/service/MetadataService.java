package com.moviefinder.service;

import com.moviefinder.config.TmdbConfig;
import com.moviefinder.model.Movie;
import com.moviefinder.resilience.CircuitBreaker;
import com.moviefinder.resilience.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final RestTemplate restTemplate;
    private final TmdbConfig tmdbConfig;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CircuitBreaker<Map<String, Object>> circuitBreaker;
    private final RateLimiter rateLimiter;

    public record EnrichmentStats(int cacheHits, int apiCalls, int errors) {}

    public MetadataService(RestTemplate restTemplate, TmdbConfig tmdbConfig,
                           RedisTemplate<String, Object> redisTemplate) {
        this.restTemplate = restTemplate;
        this.tmdbConfig = tmdbConfig;
        this.redisTemplate = redisTemplate;

        this.circuitBreaker = new CircuitBreaker<>(
                "tmdb-api",
                5,        // failure threshold
                30000,    // reset timeout (30s)
                Collections::emptyMap
        );

        this.rateLimiter = new RateLimiter("tmdb", tmdbConfig.getRateLimit(), 10000);
    }

    public EnrichmentStats enrichMovies(List<Movie> movies) {
        int cacheHits = 0;
        int apiCalls = 0;
        int errors = 0;

        for (Movie movie : movies) {
            if (movie.getTmdbId() == null) continue;

            // Check poster cache
            String cacheKey = "tmdb:poster:" + movie.getTmdbId();
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof String posterPath) {
                    movie.setPosterPath(posterPath);
                    cacheHits++;
                    continue;
                }
            } catch (Exception e) {
                // Cache miss, proceed to API
            }

            // Rate limit check
            if (!rateLimiter.tryAcquire()) {
                log.warn("TMDb rate limit reached, skipping enrichment for tmdbId={}", movie.getTmdbId());
                errors++;
                continue;
            }

            // Fetch from TMDb API via circuit breaker
            Map<String, Object> details = circuitBreaker.execute(() -> fetchFromTmdb(movie.getTmdbId()));

            if (details != null && !details.isEmpty()) {
                String posterPath = (String) details.get("poster_path");
                String backdropPath = (String) details.get("backdrop_path");
                String overview = (String) details.get("overview");
                Number runtime = (Number) details.get("runtime");

                if (posterPath != null) {
                    movie.setPosterPath(tmdbConfig.getImageBaseUrl() + "/w500" + posterPath);
                    try {
                        redisTemplate.opsForValue().set(cacheKey, movie.getPosterPath(), 7, TimeUnit.DAYS);
                    } catch (Exception e) {
                        log.debug("Failed to cache poster path: {}", e.getMessage());
                    }
                }
                if (backdropPath != null) {
                    movie.setBackdropPath(tmdbConfig.getImageBaseUrl() + "/original" + backdropPath);
                }
                if (overview != null && movie.getOverview() == null) {
                    movie.setOverview(overview);
                }
                if (runtime != null) {
                    movie.setRuntime(runtime.intValue());
                }
                apiCalls++;
            } else {
                errors++;
            }
        }

        return new EnrichmentStats(cacheHits, apiCalls, errors);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMovieDetails(Long tmdbId) {
        // Check cache
        String cacheKey = "tmdb:detail:" + tmdbId;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception e) {
            log.debug("Cache miss for tmdb detail: {}", tmdbId);
        }

        Map<String, Object> details = circuitBreaker.execute(() -> fetchFromTmdb(tmdbId));

        if (details != null && !details.isEmpty()) {
            try {
                redisTemplate.opsForValue().set(cacheKey, details, 24, TimeUnit.HOURS);
            } catch (Exception e) {
                log.debug("Failed to cache tmdb detail: {}", e.getMessage());
            }
        }

        return details;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchFromTmdb(Long tmdbId) {
        String url = String.format("%s/movie/%d?api_key=%s&append_to_response=credits",
                tmdbConfig.getBaseUrl(), tmdbId, tmdbConfig.getApiKey());
        return restTemplate.getForObject(url, Map.class);
    }

    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    public int getRateLimiterRemaining() {
        return rateLimiter.getRemainingQuota();
    }
}
