package com.moviefinder.controller;

import com.moviefinder.service.BulkIngestionService;
import com.moviefinder.service.IngestionService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final BulkIngestionService bulkIngestionService;
    private final IngestionService ingestionService;
    private final RedisTemplate<String, Object> redisTemplate;

    public AdminController(BulkIngestionService bulkIngestionService,
                           IngestionService ingestionService,
                           RedisTemplate<String, Object> redisTemplate) {
        this.bulkIngestionService = bulkIngestionService;
        this.ingestionService = ingestionService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> startIngestion(
            @RequestParam(defaultValue = "500") int pages) {

        if (bulkIngestionService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Ingestion already in progress",
                    "status", bulkIngestionService.getStatus()
            ));
        }

        if (pages < 1 || pages > 500) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Pages must be between 1 and 500"
            ));
        }

        bulkIngestionService.startIngestion(pages);

        return ResponseEntity.accepted().body(Map.of(
                "message", "Ingestion started",
                "pages", pages,
                "estimatedMovies", pages * 20
        ));
    }

    @GetMapping("/ingest/status")
    public ResponseEntity<BulkIngestionService.IngestionStatus> getIngestionStatus() {
        return ResponseEntity.ok(bulkIngestionService.getStatus());
    }

    @PostMapping("/ingest/similar")
    public ResponseEntity<Map<String, Object>> computeSimilar() {
        if (bulkIngestionService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Ingestion already in progress"
            ));
        }
        bulkIngestionService.startSimilarOnly();
        return ResponseEntity.accepted().body(Map.of(
                "message", "SIMILAR_TO computation started"
        ));
    }

    @PostMapping("/indexes")
    public ResponseEntity<Map<String, String>> createIndexes() {
        ingestionService.ensureIndexes();
        return ResponseEntity.ok(Map.of("message", "Indexes created/verified"));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> flushCache() {
        try {
            Set<String> keys = redisTemplate.keys("search:*");
            long deleted = 0;
            if (keys != null && !keys.isEmpty()) {
                deleted = redisTemplate.delete(keys);
            }
            return ResponseEntity.ok(Map.of(
                    "message", "Search cache flushed",
                    "keysDeleted", deleted
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to flush cache: " + e.getMessage()
            ));
        }
    }
}
