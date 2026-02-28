package com.moviefinder.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviefinder.resilience.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AiSearchService {

    private static final Logger log = LoggerFactory.getLogger(AiSearchService.class);
    private static final long CACHE_TTL_SECONDS = 3600; // 1 hour

    private static final String SYSTEM_PROMPT = """
            You are a movie search query parser. Given a natural language query about movies, extract structured search parameters.

            Available moods (pick at most one, or null if none fits):
            - cozy: warm, comforting, wholesome films
            - dark: dark, gritty, intense, scary films
            - mind-bending: reality-twisting, complex, puzzle films
            - feel-good: uplifting, happy, positive films
            - adrenaline: action-packed, thrilling, heart-pounding films
            - romantic: love stories, romance films
            - nostalgic: classic, retro, beloved older films
            - thought-provoking: deep, philosophical, makes you think

            Available genres (pick any that apply, or empty list):
            Action, Adventure, Animation, Comedy, Crime, Documentary, Drama, Family,
            Fantasy, History, Horror, Music, Mystery, Romance, Science Fiction,
            Thriller, TV Movie, War, Western

            Return ONLY a JSON object with these fields:
            {
              "mood": "mood_name or null",
              "searchTerms": ["keyword1", "keyword2"],
              "genres": ["Genre1", "Genre2"],
              "explanation": "Brief explanation of interpretation"
            }

            Rules:
            - searchTerms should be specific movie title keywords the user might be looking for, NOT generic descriptions
            - If the query mentions a specific movie or franchise (e.g. "wizard" → "harry potter"), include likely title matches
            - If the query is purely about a genre/mood (e.g. "scary movies"), searchTerms can be empty
            - Always return valid JSON, nothing else
            """;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private AnthropicClient client;
    private CircuitBreaker<AiSearchResult> circuitBreaker;
    private boolean enabled = false;

    public AiSearchService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            this.client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            this.enabled = true;
            log.info("AI Search enabled with model: {}", model);
        } else {
            log.warn("AI Search disabled: ANTHROPIC_API_KEY not set");
        }
        this.circuitBreaker = new CircuitBreaker<>("ai-search", 3, 60_000, () -> null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AiSearchResult parseQuery(String userInput) {
        if (!enabled || userInput == null || userInput.isBlank()) {
            return null;
        }

        // Check cache first
        String cacheKey = buildCacheKey(userInput);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                AiSearchResult result = convertCachedResult(cached);
                if (result != null) {
                    log.debug("AI search cache hit for: {}", userInput);
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Redis cache check failed for AI search: {}", e.getMessage());
        }

        // Call Claude via circuit breaker
        AiSearchResult result = circuitBreaker.execute(() -> callClaude(userInput));
        if (result == null) {
            return null;
        }

        // Cache the result
        try {
            redisTemplate.opsForValue().set(cacheKey, result, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis cache write failed for AI search: {}", e.getMessage());
        }

        return result;
    }

    private AiSearchResult callClaude(String userInput) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(256L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userInput)
                .build();

        Message message = client.messages().create(params);
        String responseText = message.content().get(0).asText().text();

        // Strip markdown code fences if present
        String json = responseText.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }

        try {
            return objectMapper.readValue(json, AiSearchResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Claude response: " + json, e);
        }
    }

    private String buildCacheKey(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.toLowerCase().strip().getBytes(StandardCharsets.UTF_8));
            return "ai-search:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "ai-search:" + input.toLowerCase().strip().hashCode();
        }
    }

    @SuppressWarnings("unchecked")
    private AiSearchResult convertCachedResult(Object cached) {
        if (cached instanceof AiSearchResult r) return r;
        if (cached instanceof java.util.Map<?, ?> map) {
            try {
                String json = objectMapper.writeValueAsString(map);
                return objectMapper.readValue(json, AiSearchResult.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached AI result: {}", e.getMessage());
            }
        }
        return null;
    }

    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiSearchResult {
        private String mood;
        private List<String> searchTerms;
        private List<String> genres;
        private String explanation;

        public AiSearchResult() {}

        public String getMood() { return mood; }
        public void setMood(String mood) { this.mood = mood; }

        public List<String> getSearchTerms() { return searchTerms; }
        public void setSearchTerms(List<String> searchTerms) { this.searchTerms = searchTerms; }

        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }

        public String getExplanation() { return explanation; }
        public void setExplanation(String explanation) { this.explanation = explanation; }

        @Override
        public String toString() {
            return String.format("{mood=%s, searchTerms=%s, genres=%s}", mood, searchTerms, genres);
        }
    }
}
