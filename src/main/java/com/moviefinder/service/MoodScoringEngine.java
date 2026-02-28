package com.moviefinder.service;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MoodScoringEngine {

    // TMDb genre ID → genre name
    private static final Map<Integer, String> TMDB_GENRE_MAP = Map.ofEntries(
            Map.entry(28, "Action"),
            Map.entry(12, "Adventure"),
            Map.entry(16, "Animation"),
            Map.entry(35, "Comedy"),
            Map.entry(80, "Crime"),
            Map.entry(99, "Documentary"),
            Map.entry(18, "Drama"),
            Map.entry(10751, "Family"),
            Map.entry(14, "Fantasy"),
            Map.entry(36, "History"),
            Map.entry(27, "Horror"),
            Map.entry(10402, "Music"),
            Map.entry(9648, "Mystery"),
            Map.entry(10749, "Romance"),
            Map.entry(878, "Sci-Fi"),
            Map.entry(10770, "TV Movie"),
            Map.entry(53, "Thriller"),
            Map.entry(10752, "War"),
            Map.entry(37, "Western")
    );

    // Genre → { mood → weight }
    private static final Map<String, Map<String, Double>> GENRE_MOOD_WEIGHTS = Map.ofEntries(
            Map.entry("Action", Map.of(
                    "adrenaline", 0.9, "dark", 0.2, "feel-good", 0.2)),
            Map.entry("Adventure", Map.of(
                    "adrenaline", 0.6, "feel-good", 0.5, "nostalgic", 0.3, "cozy", 0.2)),
            Map.entry("Animation", Map.of(
                    "cozy", 0.7, "feel-good", 0.7, "nostalgic", 0.5)),
            Map.entry("Comedy", Map.of(
                    "feel-good", 0.8, "cozy", 0.5, "nostalgic", 0.2)),
            Map.entry("Crime", Map.of(
                    "dark", 0.7, "thought-provoking", 0.5, "adrenaline", 0.4)),
            Map.entry("Documentary", Map.of(
                    "thought-provoking", 0.8, "mind-bending", 0.3)),
            Map.entry("Drama", Map.of(
                    "thought-provoking", 0.6, "nostalgic", 0.3, "romantic", 0.2)),
            Map.entry("Family", Map.of(
                    "cozy", 0.8, "feel-good", 0.8, "nostalgic", 0.4)),
            Map.entry("Fantasy", Map.of(
                    "cozy", 0.5, "mind-bending", 0.5, "nostalgic", 0.4, "adrenaline", 0.3)),
            Map.entry("History", Map.of(
                    "thought-provoking", 0.7, "nostalgic", 0.6, "dark", 0.3)),
            Map.entry("Horror", Map.of(
                    "dark", 0.9, "adrenaline", 0.5, "mind-bending", 0.3)),
            Map.entry("Music", Map.of(
                    "feel-good", 0.7, "nostalgic", 0.5, "romantic", 0.3, "cozy", 0.3)),
            Map.entry("Mystery", Map.of(
                    "mind-bending", 0.7, "dark", 0.5, "thought-provoking", 0.6)),
            Map.entry("Romance", Map.of(
                    "romantic", 0.9, "feel-good", 0.6, "cozy", 0.5)),
            Map.entry("Sci-Fi", Map.of(
                    "mind-bending", 0.8, "thought-provoking", 0.6, "adrenaline", 0.4)),
            Map.entry("TV Movie", Map.of(
                    "cozy", 0.5, "nostalgic", 0.3)),
            Map.entry("Thriller", Map.of(
                    "adrenaline", 0.7, "dark", 0.6, "mind-bending", 0.4)),
            Map.entry("War", Map.of(
                    "dark", 0.7, "thought-provoking", 0.6, "adrenaline", 0.5, "nostalgic", 0.3)),
            Map.entry("Western", Map.of(
                    "nostalgic", 0.6, "adrenaline", 0.5, "dark", 0.3))
    );

    public String resolveGenreName(int tmdbGenreId) {
        return TMDB_GENRE_MAP.get(tmdbGenreId);
    }

    public List<String> resolveGenreNames(List<Integer> tmdbGenreIds) {
        if (tmdbGenreIds == null) return List.of();
        List<String> names = new ArrayList<>();
        for (int id : tmdbGenreIds) {
            String name = TMDB_GENRE_MAP.get(id);
            if (name != null) names.add(name);
        }
        return names;
    }

    /**
     * Compute mood scores for a movie given its genre names and popularity (0-1000 scale).
     * Returns map of mood→score, only including scores >= minScore.
     */
    public Map<String, Double> computeMoodScores(List<String> genreNames, double popularity, double minScore) {
        if (genreNames == null || genreNames.isEmpty()) return Map.of();

        Map<String, Double> moodSums = new HashMap<>();

        for (String genre : genreNames) {
            Map<String, Double> weights = GENRE_MOOD_WEIGHTS.get(genre);
            if (weights == null) continue;
            for (var entry : weights.entrySet()) {
                moodSums.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        // Normalize by genre count and add popularity bonus
        double genreCount = genreNames.size();
        double popularityBonus = Math.min(popularity / 1000.0, 1.0) * 0.05; // max 5% bonus

        Map<String, Double> result = new HashMap<>();
        for (var entry : moodSums.entrySet()) {
            double score = (entry.getValue() / genreCount) + popularityBonus;
            score = Math.min(score, 1.0); // clamp to [0,1]
            if (score >= minScore) {
                result.put(entry.getKey(), Math.round(score * 100.0) / 100.0); // 2 decimal places
            }
        }

        return result;
    }
}
