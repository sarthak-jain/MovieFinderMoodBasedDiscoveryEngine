package com.moviefinder.controller;

import com.moviefinder.model.Mood;
import com.moviefinder.model.SearchResult;
import com.moviefinder.service.SearchService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;
    private final Driver neo4jDriver;

    public SearchController(SearchService searchService, Driver neo4jDriver) {
        this.searchService = searchService;
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
