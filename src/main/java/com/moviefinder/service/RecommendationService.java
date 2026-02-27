package com.moviefinder.service;

import com.moviefinder.model.Movie;
import com.moviefinder.workflow.WorkflowStep;
import com.moviefinder.workflow.WorkflowTracer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final Driver neo4jDriver;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MetadataService metadataService;
    private final WorkflowTracer workflowTracer;

    public RecommendationService(Driver neo4jDriver, RedisTemplate<String, Object> redisTemplate,
                                  MetadataService metadataService, WorkflowTracer workflowTracer) {
        this.neo4jDriver = neo4jDriver;
        this.redisTemplate = redisTemplate;
        this.metadataService = metadataService;
        this.workflowTracer = workflowTracer;
    }

    @SuppressWarnings("unchecked")
    public List<Movie> getSimilarMovies(Long movieId) {
        WorkflowTracer.Trace trace = workflowTracer.startTrace("GET", "/api/movie/" + movieId + "/similar");
        trace.emitApiGateway("Routing GET /api/movie/" + movieId + "/similar → RecommendationService");

        // Check cache
        String cacheKey = "similar:" + movieId;
        long cacheStart = System.nanoTime();
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            if (cached instanceof List<?> list) {
                trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheDuration);
                trace.emitResponse(200, list.size());
                return (List<Movie>) cached;
            }
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
        } catch (Exception e) {
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
        }

        // Graph traversal for similar movies
        long dbStart = System.nanoTime();
        String cypher = """
                MATCH (m:Movie)-[:SIMILAR_TO]->(similar:Movie)
                WHERE id(m) = $movieId
                OPTIONAL MATCH (similar)-[:HAS_GENRE]->(g:Genre)
                RETURN similar, collect(DISTINCT g) as genres
                ORDER BY similar.avgRating DESC
                LIMIT 10
                """;

        List<Movie> similar = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            var result = session.run(cypher, Values.value(java.util.Map.of("movieId", movieId)));
            while (result.hasNext()) {
                Record record = result.next();
                similar.add(mapRecordToMovie(record, "similar"));
            }
        }

        long dbDuration = (System.nanoTime() - dbStart) / 1_000_000;
        String graphDetail = String.format("Traversing SIMILAR_TO edges from Movie #%d — depth 1, limit 10 — returned %d", movieId, similar.size());
        trace.emitGraphQuery(graphDetail, cypher.trim(), similar.size(), dbDuration);

        // If no SIMILAR_TO edges, fall back to shared-genre traversal
        if (similar.isEmpty()) {
            dbStart = System.nanoTime();
            String fallbackCypher = """
                    MATCH (m:Movie)-[:HAS_GENRE]->(g:Genre)<-[:HAS_GENRE]-(similar:Movie)
                    WHERE id(m) = $movieId AND id(similar) <> $movieId
                    WITH similar, count(g) as sharedGenres
                    ORDER BY sharedGenres DESC, similar.avgRating DESC
                    LIMIT 10
                    OPTIONAL MATCH (similar)-[:HAS_GENRE]->(g2:Genre)
                    RETURN similar, collect(DISTINCT g2) as genres
                    """;

            try (Session session = neo4jDriver.session()) {
                var result = session.run(fallbackCypher, Values.value(java.util.Map.of("movieId", movieId)));
                while (result.hasNext()) {
                    Record record = result.next();
                    similar.add(mapRecordToMovie(record, "similar"));
                }
            }
            dbDuration = (System.nanoTime() - dbStart) / 1_000_000;
            String fallbackDetail = String.format("Fallback: shared-genre traversal (Movie→Genre←Movie), ranking by overlap count — returned %d", similar.size());
            trace.emitGraphQuery(fallbackDetail, fallbackCypher.trim(), similar.size(), dbDuration);
        }

        // Enrich with posters
        long enrichStart = System.nanoTime();
        MetadataService.EnrichmentStats stats = metadataService.enrichMovies(similar);
        long enrichDuration = (System.nanoTime() - enrichStart) / 1_000_000;
        String enrichDetail = String.format("TMDb batch poster fetch for %d similar movies", similar.size());
        trace.emitExternalApi("TMDb", enrichDetail, stats.cacheHits(), stats.apiCalls(), enrichDuration);

        // Resilience status
        trace.emitCircuitBreaker(metadataService.getCircuitBreakerState().name(), 0);
        trace.emitRateLimit(metadataService.getRateLimiterRemaining(), 30);

        // Cache results
        long cacheWriteStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set(cacheKey, similar, 1, TimeUnit.HOURS);
            long cacheWriteDuration = (System.nanoTime() - cacheWriteStart) / 1_000_000;
            trace.emitCacheWrite(cacheKey, "1h", cacheWriteDuration);
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }

        trace.emitResponse(200, similar.size());
        return similar;
    }

    private Movie mapRecordToMovie(Record record, String alias) {
        var node = record.get(alias).asNode();
        Movie movie = new Movie();
        movie.setId(node.id());
        movie.setTitle(node.get("title").asString(null));
        movie.setYear(node.get("year").isNull() ? null : node.get("year").asInt());
        movie.setOverview(node.get("overview").asString(null));
        movie.setAvgRating(node.get("avgRating").isNull() ? null : node.get("avgRating").asDouble());
        movie.setPosterPath(node.get("posterPath").asString(null));
        movie.setTmdbId(node.get("tmdbId").isNull() ? null : node.get("tmdbId").asLong());
        return movie;
    }
}
