package com.moviefinder.service;

import com.moviefinder.config.TmdbConfig;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BulkIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BulkIngestionService.class);

    private final Driver neo4jDriver;
    private final RestTemplate restTemplate;
    private final TmdbConfig tmdbConfig;
    private final MoodScoringEngine moodScoringEngine;

    @Value("${moviefinder.ingestion.batch-size:500}")
    private int batchSize;

    @Value("${moviefinder.ingestion.discover-delay-ms:250}")
    private int discoverDelayMs;

    @Value("${moviefinder.ingestion.min-mood-score:0.25}")
    private double minMoodScore;

    @Value("${moviefinder.ingestion.max-similar:20}")
    private int maxSimilar;

    private final AtomicReference<IngestionStatus> status = new AtomicReference<>(IngestionStatus.idle());

    public BulkIngestionService(Driver neo4jDriver, RestTemplate restTemplate,
                                TmdbConfig tmdbConfig, MoodScoringEngine moodScoringEngine) {
        this.neo4jDriver = neo4jDriver;
        this.restTemplate = restTemplate;
        this.tmdbConfig = tmdbConfig;
        this.moodScoringEngine = moodScoringEngine;
    }

    public IngestionStatus getStatus() {
        return status.get();
    }

    public boolean isRunning() {
        return status.get().phase() != Phase.IDLE && status.get().phase() != Phase.COMPLETE
                && status.get().phase() != Phase.FAILED;
    }

    @Async
    public void startSimilarOnly() {
        if (isRunning()) {
            log.warn("Ingestion already in progress, ignoring request");
            return;
        }

        log.info("Starting SIMILAR_TO computation only...");
        Instant startTime = Instant.now();

        try {
            status.set(new IngestionStatus(Phase.COMPUTING_SIMILAR, 0, 0, 0, 0, null, startTime, null));
            int similarEdges = computeSimilarTo();
            Instant endTime = Instant.now();
            status.set(new IngestionStatus(Phase.COMPLETE, 0, 0, 0, 0, null, startTime, endTime));
            log.info("SIMILAR_TO computation complete: {} edges in {}s", similarEdges,
                    (endTime.toEpochMilli() - startTime.toEpochMilli()) / 1000);
        } catch (Exception e) {
            log.error("SIMILAR_TO computation failed", e);
            status.set(new IngestionStatus(Phase.FAILED, 0, 0, 0, 0, e.getMessage(), startTime, Instant.now()));
        }
    }

    @Async
    public void startIngestion(int pages) {
        if (isRunning()) {
            log.warn("Ingestion already in progress, ignoring request");
            return;
        }

        log.info("Starting bulk ingestion: {} pages ({} movies)", pages, pages * 20);
        Instant startTime = Instant.now();

        try {
            // Phase 1: Fetch from TMDb
            status.set(new IngestionStatus(Phase.FETCHING, 0, pages * 20, 0, 0, null, startTime, null));
            List<Map<String, Object>> allMovies = fetchFromTmdb(pages);
            log.info("Fetched {} movies from TMDb", allMovies.size());

            // Phase 2: Write movies to Neo4j
            status.set(new IngestionStatus(Phase.WRITING_MOVIES, 0, allMovies.size(), allMovies.size(), 0, null, startTime, null));
            writeMovies(allMovies);
            log.info("Wrote {} movies to Neo4j", allMovies.size());

            // Phase 3: Write mood relationships
            status.set(new IngestionStatus(Phase.WRITING_MOODS, 0, allMovies.size(), allMovies.size(), 0, null, startTime, null));
            int moodEdges = writeMoods(allMovies);
            log.info("Created {} mood edges", moodEdges);

            // Phase 4: Compute SIMILAR_TO relationships
            status.set(new IngestionStatus(Phase.COMPUTING_SIMILAR, 0, 0, allMovies.size(), moodEdges, null, startTime, null));
            int similarEdges = computeSimilarTo();
            log.info("Created {} SIMILAR_TO edges", similarEdges);

            Instant endTime = Instant.now();
            status.set(new IngestionStatus(Phase.COMPLETE, allMovies.size(), allMovies.size(), allMovies.size(), moodEdges, null, startTime, endTime));
            log.info("Bulk ingestion complete: {} movies, {} mood edges, {} similar edges in {}s",
                    allMovies.size(), moodEdges, similarEdges,
                    (endTime.toEpochMilli() - startTime.toEpochMilli()) / 1000);

        } catch (Exception e) {
            log.error("Bulk ingestion failed", e);
            status.set(new IngestionStatus(Phase.FAILED, status.get().processed(), status.get().total(),
                    status.get().moviesFetched(), status.get().moodEdges(), e.getMessage(), startTime, Instant.now()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFromTmdb(int pages) {
        List<Map<String, Object>> allMovies = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        for (int page = 1; page <= pages; page++) {
            try {
                String url = String.format(
                        "%s/discover/movie?api_key=%s&sort_by=popularity.desc&page=%d&vote_count.gte=50&language=en-US",
                        tmdbConfig.getBaseUrl(), tmdbConfig.getApiKey(), page);

                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response == null) continue;

                List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
                if (results == null) continue;

                for (Map<String, Object> movie : results) {
                    Number idNum = (Number) movie.get("id");
                    if (idNum == null) continue;
                    long tmdbId = idNum.longValue();
                    if (seenIds.contains(tmdbId)) continue;
                    seenIds.add(tmdbId);

                    String title = (String) movie.get("title");
                    if (title == null || title.isBlank()) continue;

                    String releaseDate = (String) movie.get("release_date");
                    Integer year = null;
                    if (releaseDate != null && releaseDate.length() >= 4) {
                        try {
                            year = Integer.parseInt(releaseDate.substring(0, 4));
                        } catch (NumberFormatException ignored) {}
                    }

                    String overview = (String) movie.get("overview");
                    Number voteAvg = (Number) movie.get("vote_average");
                    Number popularity = (Number) movie.get("popularity");
                    String posterPath = (String) movie.get("poster_path");

                    List<Integer> genreIds = (List<Integer>) movie.get("genre_ids");
                    List<String> genreNames = moodScoringEngine.resolveGenreNames(genreIds);

                    double pop = popularity != null ? popularity.doubleValue() : 0;
                    Map<String, Double> moodScores = moodScoringEngine.computeMoodScores(genreNames, pop, minMoodScore);

                    Map<String, Object> movieData = new HashMap<>();
                    movieData.put("tmdbId", tmdbId);
                    movieData.put("title", title);
                    movieData.put("year", year);
                    movieData.put("overview", overview != null ? overview : "");
                    movieData.put("avgRating", voteAvg != null ? voteAvg.doubleValue() : 0.0);
                    movieData.put("posterPath", posterPath != null
                            ? tmdbConfig.getImageBaseUrl() + "/w500" + posterPath : null);
                    movieData.put("popularity", pop);
                    movieData.put("genres", genreNames);
                    movieData.put("moodScores", moodScores);

                    allMovies.add(movieData);
                }

                status.set(new IngestionStatus(Phase.FETCHING, page, pages,
                        allMovies.size(), 0, null, status.get().startTime(), null));

                if (page < pages) {
                    Thread.sleep(discoverDelayMs);
                }

                if (page % 50 == 0) {
                    log.info("Fetched page {}/{} ({} movies so far)", page, pages, allMovies.size());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Ingestion interrupted during fetch", e);
            } catch (Exception e) {
                log.warn("Failed to fetch page {}: {}", page, e.getMessage());
            }
        }

        return allMovies;
    }

    private void writeMovies(List<Map<String, Object>> movies) {
        String cypher = """
                UNWIND $movies AS m
                MERGE (movie:Movie {tmdbId: m.tmdbId})
                SET movie.title = m.title,
                    movie.year = m.year,
                    movie.overview = m.overview,
                    movie.avgRating = m.avgRating,
                    movie.posterPath = m.posterPath,
                    movie.popularity = m.popularity
                WITH movie, m
                UNWIND m.genres AS genreName
                MERGE (g:Genre {name: genreName})
                MERGE (movie)-[:HAS_GENRE]->(g)
                """;

        writeBatches(movies, cypher, "movies");
    }

    @SuppressWarnings("unchecked")
    private int writeMoods(List<Map<String, Object>> movies) {
        int totalEdges = 0;
        List<Map<String, Object>> moodBatch = new ArrayList<>();

        for (Map<String, Object> movie : movies) {
            Map<String, Double> moodScores = (Map<String, Double>) movie.get("moodScores");
            if (moodScores == null || moodScores.isEmpty()) continue;

            for (var entry : moodScores.entrySet()) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("tmdbId", movie.get("tmdbId"));
                edge.put("mood", entry.getKey());
                edge.put("score", entry.getValue());
                moodBatch.add(edge);
                totalEdges++;

                if (moodBatch.size() >= batchSize) {
                    writeMoodBatch(moodBatch);
                    moodBatch.clear();
                }
            }
        }

        if (!moodBatch.isEmpty()) {
            writeMoodBatch(moodBatch);
        }

        return totalEdges;
    }

    private void writeMoodBatch(List<Map<String, Object>> batch) {
        String cypher = """
                UNWIND $edges AS e
                MATCH (movie:Movie {tmdbId: e.tmdbId})
                MERGE (mood:Mood {name: e.mood})
                MERGE (movie)-[r:MATCHES_MOOD]->(mood)
                SET r.score = e.score
                """;

        try (Session session = neo4jDriver.session()) {
            session.run(cypher, Values.value(Map.of("edges", batch)));
        }
    }

    private int computeSimilarTo() {
        log.info("Computing SIMILAR_TO relationships (max {} per movie) in batches...", maxSimilar);

        // Get all movie tmdbIds to process in batches
        List<Long> allTmdbIds;
        try (Session session = neo4jDriver.session()) {
            var result = session.run("MATCH (m:Movie) RETURN m.tmdbId AS tmdbId ORDER BY m.tmdbId");
            allTmdbIds = new ArrayList<>();
            while (result.hasNext()) {
                allTmdbIds.add(result.next().get("tmdbId").asLong());
            }
        }

        String cypher = """
                UNWIND $tmdbIds AS id
                MATCH (m1:Movie {tmdbId: id})-[:HAS_GENRE]->(g:Genre)<-[:HAS_GENRE]-(m2:Movie)
                WHERE m1.tmdbId < m2.tmdbId
                WITH m1, m2, count(g) AS shared
                WHERE shared >= 2
                WITH m1, m2, toFloat(shared) / 5.0 AS score
                ORDER BY m1.tmdbId, score DESC
                WITH m1, collect(m2)[0..$maxSimilar] AS topMovies, collect(score)[0..$maxSimilar] AS topScores
                WITH m1, topMovies, topScores, range(0, size(topMovies)-1) AS idxs
                UNWIND idxs AS idx
                WITH m1, topMovies[idx] AS m2, topScores[idx] AS score
                MERGE (m1)-[r:SIMILAR_TO]->(m2)
                SET r.score = score
                RETURN count(r) AS edgeCount
                """;

        int totalEdges = 0;
        int similarBatchSize = 200;
        for (int i = 0; i < allTmdbIds.size(); i += similarBatchSize) {
            List<Long> batch = allTmdbIds.subList(i, Math.min(i + similarBatchSize, allTmdbIds.size()));
            try (Session session = neo4jDriver.session()) {
                var result = session.run(cypher, Values.value(Map.of("tmdbIds", batch, "maxSimilar", maxSimilar)));
                if (result.hasNext()) {
                    totalEdges += result.next().get("edgeCount").asInt();
                }
            }
            if ((i / similarBatchSize) % 10 == 0) {
                log.info("SIMILAR_TO progress: {}/{} movies, {} edges so far",
                        Math.min(i + similarBatchSize, allTmdbIds.size()), allTmdbIds.size(), totalEdges);
            }
        }

        return totalEdges;
    }

    private void writeBatches(List<Map<String, Object>> items, String cypher, String label) {
        int total = items.size();
        for (int i = 0; i < total; i += batchSize) {
            List<Map<String, Object>> batch = items.subList(i, Math.min(i + batchSize, total));
            try (Session session = neo4jDriver.session()) {
                session.run(cypher, Values.value(Map.of("movies", batch)));
            }
            int processed = Math.min(i + batchSize, total);
            status.set(new IngestionStatus(status.get().phase(), processed, total,
                    status.get().moviesFetched(), status.get().moodEdges(),
                    null, status.get().startTime(), null));
            log.info("Wrote {}/{} {}", processed, total, label);
        }
    }

    public enum Phase {
        IDLE, FETCHING, WRITING_MOVIES, WRITING_MOODS, COMPUTING_SIMILAR, COMPLETE, FAILED
    }

    public record IngestionStatus(
            Phase phase,
            int processed,
            int total,
            int moviesFetched,
            int moodEdges,
            String error,
            Instant startTime,
            Instant endTime
    ) {
        public static IngestionStatus idle() {
            return new IngestionStatus(Phase.IDLE, 0, 0, 0, 0, null, null, null);
        }

        public long elapsedSeconds() {
            if (startTime == null) return 0;
            Instant end = endTime != null ? endTime : Instant.now();
            return (end.toEpochMilli() - startTime.toEpochMilli()) / 1000;
        }
    }
}
