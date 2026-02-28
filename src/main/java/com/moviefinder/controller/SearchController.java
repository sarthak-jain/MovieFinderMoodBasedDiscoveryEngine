package com.moviefinder.controller;

import com.moviefinder.model.AiSearchResponse;
import com.moviefinder.model.Mood;
import com.moviefinder.model.SearchResult;
import com.moviefinder.service.AiSearchService;
import com.moviefinder.service.SearchService;
import com.moviefinder.workflow.WorkflowTracer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;
    private final AiSearchService aiSearchService;
    private final WorkflowTracer workflowTracer;
    private final Driver neo4jDriver;

    public SearchController(SearchService searchService, AiSearchService aiSearchService,
                            WorkflowTracer workflowTracer, Driver neo4jDriver) {
        this.searchService = searchService;
        this.aiSearchService = aiSearchService;
        this.workflowTracer = workflowTracer;
        this.neo4jDriver = neo4jDriver;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResult> search(
            @RequestParam(required = false) String mood,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page) {

        SearchResult result = searchService.search(mood, query, page);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/search/ai")
    public ResponseEntity<AiSearchResponse> aiSearch(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        int page = 0;
        if (body.containsKey("page")) {
            try { page = Integer.parseInt(body.get("page")); } catch (NumberFormatException ignored) {}
        }

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (!aiSearchService.isEnabled()) {
            return ResponseEntity.status(503).build();
        }

        WorkflowTracer.Trace trace = workflowTracer.startTrace("POST", "/api/search/ai?query=" + query);
        trace.emitApiGateway("Routing POST /api/search/ai → AiSearchService + SearchService");

        // Call Claude to parse the query
        long aiStart = System.nanoTime();
        AiSearchService.AiSearchResult parsed = aiSearchService.parseQuery(query);
        long aiDuration = (System.nanoTime() - aiStart) / 1_000_000;

        if (parsed == null) {
            trace.emitError("AI Query Parser", "Claude API unavailable — circuit breaker open or API key missing", aiDuration);
            trace.emitResponse(503, 0);
            return ResponseEntity.status(503).build();
        }

        trace.emitAiQueryParser(query, parsed.toString(), aiDuration);

        // Execute the parsed search
        SearchResult results = searchService.aiSearch(
                parsed.getMood(), parsed.getSearchTerms(), parsed.getGenres(), page, trace);

        return ResponseEntity.ok(new AiSearchResponse(parsed, results));
    }

    @GetMapping("/suggest")
    public ResponseEntity<List<Map<String, Object>>> suggest(@RequestParam String q) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(List.of());
        }

        List<Map<String, Object>> suggestions = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String queryParam = q.trim() + "*";
            String cypher = """
                    CALL db.index.fulltext.queryNodes('movie_title_fulltext', $q)
                    YIELD node AS m, score
                    RETURN m.title AS title, m.year AS year, id(m) AS id
                    ORDER BY score DESC, m.avgRating DESC
                    LIMIT 8
                    """;
            var result = session.run(cypher, Values.value(Map.of("q", queryParam)));
            while (result.hasNext()) {
                var record = result.next();
                suggestions.add(Map.of(
                        "id", record.get("id").asLong(),
                        "title", record.get("title").asString(),
                        "year", record.get("year").isNull() ? "" : record.get("year").asInt()
                ));
            }
        }
        return ResponseEntity.ok(suggestions);
    }

    @GetMapping("/moods")
    public ResponseEntity<List<Mood>> getMoods() {
        List<Mood> moods = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            var result = session.run("MATCH (m:Mood) RETURN m ORDER BY m.name");
            while (result.hasNext()) {
                var record = result.next();
                var node = record.get("m").asNode();
                Mood mood = new Mood();
                mood.setName(node.get("name").asString(null));
                mood.setDescription(node.get("description").asString(null));
                mood.setEmoji(node.get("emoji").asString(null));
                moods.add(mood);
            }
        }
        return ResponseEntity.ok(moods);
    }
}
