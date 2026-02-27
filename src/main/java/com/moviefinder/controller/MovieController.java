package com.moviefinder.controller;

import com.moviefinder.model.Movie;
import com.moviefinder.service.MetadataService;
import com.moviefinder.service.RecommendationService;
import com.moviefinder.workflow.WorkflowStep;
import com.moviefinder.workflow.WorkflowTracer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/movie")
public class MovieController {

    private final Driver neo4jDriver;
    private final RecommendationService recommendationService;
    private final MetadataService metadataService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WorkflowTracer workflowTracer;

    public MovieController(Driver neo4jDriver, RecommendationService recommendationService,
                           MetadataService metadataService, RedisTemplate<String, Object> redisTemplate,
                           WorkflowTracer workflowTracer) {
        this.neo4jDriver = neo4jDriver;
        this.recommendationService = recommendationService;
        this.metadataService = metadataService;
        this.redisTemplate = redisTemplate;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getMovie(@PathVariable Long id) {
        WorkflowTracer.Trace trace = workflowTracer.startTrace("GET", "/api/movie/" + id);
        trace.emitApiGateway("Routing GET /api/movie/" + id + " → MovieDetailService");

        // Cache check
        String cacheKey = "movie:detail:" + id;
        long cacheStart = System.nanoTime();
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            if (cached instanceof Map<?, ?> map) {
                trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheDuration);
                trace.emitResponse(200, 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return ResponseEntity.ok(result);
            }
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
        } catch (Exception e) {
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
        }

        // Graph DB query
        long dbStart = System.nanoTime();
        String cypher = """
                MATCH (m:Movie) WHERE id(m) = $id
                OPTIONAL MATCH (m)-[:HAS_GENRE]->(g:Genre)
                OPTIONAL MATCH (m)-[r:MATCHES_MOOD]->(mood:Mood)
                OPTIONAL MATCH (p:Person)-[act:ACTED_IN]->(m)
                OPTIONAL MATCH (d:Person)-[:DIRECTED]->(m)
                RETURN m,
                       collect(DISTINCT g.name) as genres,
                       collect(DISTINCT {mood: mood.name, score: r.score}) as moods,
                       collect(DISTINCT {name: p.name, role: act.role}) as cast,
                       collect(DISTINCT d.name) as directors
                """;

        Map<String, Object> movieData = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            var result = session.run(cypher, Values.value(Map.of("id", id)));
            if (result.hasNext()) {
                var record = result.next();
                var node = record.get("m").asNode();

                movieData.put("id", node.id());
                movieData.put("title", node.get("title").asString(null));
                movieData.put("year", node.get("year").isNull() ? null : node.get("year").asInt());
                movieData.put("overview", node.get("overview").asString(null));
                movieData.put("avgRating", node.get("avgRating").isNull() ? null : node.get("avgRating").asDouble());
                movieData.put("posterPath", node.get("posterPath").asString(null));
                movieData.put("tmdbId", node.get("tmdbId").isNull() ? null : node.get("tmdbId").asLong());
                movieData.put("genres", record.get("genres").asList(org.neo4j.driver.Value::asString));
                movieData.put("moods", record.get("moods").asList(v -> v.asMap()));
                movieData.put("cast", record.get("cast").asList(v -> v.asMap()));
                movieData.put("directors", record.get("directors").asList(org.neo4j.driver.Value::asString));
            }
        }

        long dbDuration = (System.nanoTime() - dbStart) / 1_000_000;
        String graphDetail = "Fetching Movie node + Genre/Mood/Cast/Director relationships via 5-hop traversal";
        trace.emitGraphQuery(graphDetail, cypher.trim(), movieData.isEmpty() ? 0 : 1, dbDuration);

        if (movieData.isEmpty()) {
            trace.emitResponse(404, 0);
            return ResponseEntity.notFound().build();
        }

        // Enrich with TMDb data
        Long tmdbId = (Long) movieData.get("tmdbId");
        if (tmdbId != null) {
            long enrichStart = System.nanoTime();
            Map<String, Object> tmdbData = metadataService.getMovieDetails(tmdbId);
            long enrichDuration = (System.nanoTime() - enrichStart) / 1_000_000;

            if (tmdbData != null && !tmdbData.isEmpty()) {
                if (tmdbData.get("poster_path") != null) {
                    movieData.put("posterPath", "https://image.tmdb.org/t/p/w500" + tmdbData.get("poster_path"));
                }
                if (tmdbData.get("backdrop_path") != null) {
                    movieData.put("backdropPath", "https://image.tmdb.org/t/p/original" + tmdbData.get("backdrop_path"));
                }
                if (tmdbData.get("runtime") != null) {
                    movieData.put("runtime", tmdbData.get("runtime"));
                }
                if (tmdbData.get("tagline") != null) {
                    movieData.put("tagline", tmdbData.get("tagline"));
                }
                String tmdbDetail = String.format("TMDb API — fetching poster, backdrop, runtime, tagline for tmdbId=%d", tmdbId);
                trace.emitExternalApi("TMDb", tmdbDetail, 0, 1, enrichDuration);
            } else {
                trace.emitExternalApi("TMDb", "Fetch failed — using fallback data", 0, 0, enrichDuration);
            }
            trace.emitCircuitBreaker(metadataService.getCircuitBreakerState().name(), 0);
            trace.emitRateLimit(metadataService.getRateLimiterRemaining(), 30);
        }

        // Cache the result
        long cacheWriteStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set(cacheKey, movieData, 24, TimeUnit.HOURS);
            long cacheWriteDuration = (System.nanoTime() - cacheWriteStart) / 1_000_000;
            trace.emitCacheWrite(cacheKey, "24h", cacheWriteDuration);
        } catch (Exception e) {
            // Cache write failure is non-fatal
        }

        trace.emitResponse(200, 1);
        return ResponseEntity.ok(movieData);
    }

    @GetMapping("/{id}/similar")
    public ResponseEntity<List<Movie>> getSimilarMovies(@PathVariable Long id) {
        List<Movie> similar = recommendationService.getSimilarMovies(id);
        return ResponseEntity.ok(similar);
    }
}
