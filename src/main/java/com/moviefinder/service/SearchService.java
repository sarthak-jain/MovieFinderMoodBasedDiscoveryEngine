package com.moviefinder.service;

import com.moviefinder.model.Movie;
import com.moviefinder.model.SearchResult;
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

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int PAGE_SIZE = 20;

    private final Driver neo4jDriver;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MetadataService metadataService;
    private final WorkflowTracer workflowTracer;

    public SearchService(Driver neo4jDriver, RedisTemplate<String, Object> redisTemplate,
                         MetadataService metadataService, WorkflowTracer workflowTracer) {
        this.neo4jDriver = neo4jDriver;
        this.redisTemplate = redisTemplate;
        this.metadataService = metadataService;
        this.workflowTracer = workflowTracer;
    }

    @SuppressWarnings("unchecked")
    public SearchResult search(String mood, String query, int page) {
        WorkflowTracer.Trace trace = workflowTracer.startTrace("GET",
                "/api/search?mood=" + mood + "&query=" + (query != null ? query : ""));

        // Step 1: API Gateway routing
        trace.emitApiGateway("Routing GET /api/search → SearchService.search()");

        // Step 2: Check cache
        String cacheKey = buildCacheKey(mood, query, page);
        long cacheStart = System.nanoTime();
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            if (cached != null) {
                trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheDuration);
                SearchResult result = convertCachedResult(cached);
                trace.emitResponse(200, result.getMovies().size());
                return result;
            }
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
        } catch (Exception e) {
            long cacheDuration = (System.nanoTime() - cacheStart) / 1_000_000;
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheDuration);
            log.warn("Redis cache check failed: {}", e.getMessage());
        }

        // Step 3: Graph DB query
        long dbStart = System.nanoTime();
        String cypherQuery = buildCypherQuery(mood, query);
        List<Movie> movies;
        int totalResults;

        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("skip", page * PAGE_SIZE);
            params.put("limit", PAGE_SIZE);

            if (mood != null && !mood.isEmpty()) {
                params.put("mood", mood.toLowerCase());
            }
            if (query != null && !query.isEmpty()) {
                params.put("query", "(?i).*" + query + ".*");
            }

            var result = session.run(cypherQuery, Values.value(params));
            movies = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                movies.add(mapRecordToMovie(record));
            }

            // Get total count
            String countQuery = buildCountQuery(mood, query);
            var countResult = session.run(countQuery, Values.value(params));
            totalResults = countResult.hasNext() ? countResult.next().get("count").asInt() : movies.size();
        }

        long dbDuration = (System.nanoTime() - dbStart) / 1_000_000;
        String graphDetail = String.format("Executing Cypher: mood-based traversal across Movie→Mood→Genre subgraph — returned %d movies", movies.size());
        trace.emitGraphQuery(graphDetail, cypherQuery, movies.size(), dbDuration);

        // Step 4: Enrich with TMDb metadata (posters)
        long enrichStart = System.nanoTime();
        MetadataService.EnrichmentStats stats = metadataService.enrichMovies(movies);
        long enrichDuration = (System.nanoTime() - enrichStart) / 1_000_000;
        String enrichDetail = String.format("TMDb API v3 batch enrichment — fetching poster/backdrop metadata for %d movies", movies.size());
        trace.emitExternalApi("TMDb", enrichDetail, stats.cacheHits(), stats.apiCalls(), enrichDuration);

        // Step 4b: Circuit Breaker + Rate Limiter status
        trace.emitCircuitBreaker(metadataService.getCircuitBreakerState().name(), 0);
        trace.emitRateLimit(metadataService.getRateLimiterRemaining(), 30);

        // Step 5: Ranking
        long rankStart = System.nanoTime();
        rankMovies(movies, mood);
        long rankDuration = (System.nanoTime() - rankStart) / 1_000_000;
        String rankFormula = String.format("Applying weighted scoring: mood_relevance × 0.6 + normalized_rating × 0.4, sorting %d results", movies.size());
        trace.emitRanking(rankFormula, rankDuration);

        // Step 6: Cache the results
        SearchResult searchResult = new SearchResult(movies, totalResults, page, mood, query, trace.elapsedMs());
        long cacheWriteStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set(cacheKey, searchResult, 5, TimeUnit.MINUTES);
            long cacheWriteDuration = (System.nanoTime() - cacheWriteStart) / 1_000_000;
            trace.emitCacheWrite(cacheKey, "5min", cacheWriteDuration);
        } catch (Exception e) {
            log.warn("Redis cache write failed: {}", e.getMessage());
        }

        // Step 7: Response
        trace.emitResponse(200, movies.size());

        return searchResult;
    }

    private String buildCacheKey(String mood, String query, int page) {
        return String.format("search:%s:%s:%d",
                mood != null ? mood.toLowerCase() : "all",
                query != null ? query.toLowerCase() : "",
                page);
    }

    private String buildCypherQuery(String mood, String query) {
        StringBuilder cypher = new StringBuilder();

        if (mood != null && !mood.isEmpty()) {
            cypher.append("MATCH (m:Movie)-[r:MATCHES_MOOD]->(mood:Mood {name: $mood}) ");
        } else {
            cypher.append("MATCH (m:Movie) ");
        }

        if (query != null && !query.isEmpty()) {
            cypher.append("WHERE m.title =~ $query ");
        }

        cypher.append("OPTIONAL MATCH (m)-[:HAS_GENRE]->(g:Genre) ");

        if (mood != null && !mood.isEmpty()) {
            cypher.append("RETURN m, collect(DISTINCT g) as genres, r.score as moodScore ");
            cypher.append("ORDER BY r.score DESC, m.avgRating DESC ");
        } else {
            cypher.append("RETURN m, collect(DISTINCT g) as genres, null as moodScore ");
            cypher.append("ORDER BY m.avgRating DESC ");
        }

        cypher.append("SKIP $skip LIMIT $limit");
        return cypher.toString();
    }

    private String buildCountQuery(String mood, String query) {
        StringBuilder cypher = new StringBuilder();

        if (mood != null && !mood.isEmpty()) {
            cypher.append("MATCH (m:Movie)-[r:MATCHES_MOOD]->(mood:Mood {name: $mood}) ");
        } else {
            cypher.append("MATCH (m:Movie) ");
        }

        if (query != null && !query.isEmpty()) {
            cypher.append("WHERE m.title =~ $query ");
        }

        cypher.append("RETURN count(m) as count");
        return cypher.toString();
    }

    private Movie mapRecordToMovie(Record record) {
        var node = record.get("m").asNode();
        Movie movie = new Movie();
        movie.setId(node.id());
        movie.setTitle(node.get("title").asString(null));
        movie.setYear(node.get("year").isNull() ? null : node.get("year").asInt());
        movie.setOverview(node.get("overview").asString(null));
        movie.setAvgRating(node.get("avgRating").isNull() ? null : node.get("avgRating").asDouble());
        movie.setPosterPath(node.get("posterPath").asString(null));
        movie.setTmdbId(node.get("tmdbId").isNull() ? null : node.get("tmdbId").asLong());

        if (!record.get("moodScore").isNull()) {
            movie.setMoodScore(record.get("moodScore").asDouble());
        }

        return movie;
    }

    private void rankMovies(List<Movie> movies, String mood) {
        for (Movie movie : movies) {
            double moodScore = movie.getMoodScore() != null ? movie.getMoodScore() : 0.5;
            double rating = movie.getAvgRating() != null ? movie.getAvgRating() / 10.0 : 0.5;
            movie.setRelevanceScore(moodScore * 0.6 + rating * 0.4);
        }
        movies.sort(Comparator.comparingDouble(Movie::getRelevanceScore).reversed());
    }

    @SuppressWarnings("unchecked")
    private SearchResult convertCachedResult(Object cached) {
        if (cached instanceof SearchResult sr) return sr;
        // Handle deserialization from Redis
        if (cached instanceof Map<?, ?> map) {
            SearchResult result = new SearchResult();
            result.setMood((String) map.get("mood"));
            result.setQuery((String) map.get("query"));
            Number totalResults = (Number) map.get("totalResults");
            result.setTotalResults(totalResults != null ? totalResults.intValue() : 0);
            Number page = (Number) map.get("page");
            result.setPage(page != null ? page.intValue() : 0);
            result.setMovies(Collections.emptyList());
            return result;
        }
        return new SearchResult();
    }
}
