package com.moviefinder.model;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Node("Movie")
public class Movie {

    @Id
    @GeneratedValue
    private Long id;

    @Property("tmdbId")
    private Long tmdbId;

    private String title;
    private Integer year;
    private String overview;
    private Double avgRating;
    private String posterPath;
    private String backdropPath;
    private Integer runtime;

    @Relationship(type = "HAS_GENRE", direction = Relationship.Direction.OUTGOING)
    private Set<Genre> genres = new HashSet<>();

    // Transient fields populated at query time
    private Double moodScore;
    private Double relevanceScore;
    private Map<String, Double> moodScores;

    public Movie() {}

    public Movie(Long tmdbId, String title, Integer year, String overview) {
        this.tmdbId = tmdbId;
        this.title = title;
        this.year = year;
        this.overview = overview;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTmdbId() { return tmdbId; }
    public void setTmdbId(Long tmdbId) { this.tmdbId = tmdbId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }

    public Double getAvgRating() { return avgRating; }
    public void setAvgRating(Double avgRating) { this.avgRating = avgRating; }

    public String getPosterPath() { return posterPath; }
    public void setPosterPath(String posterPath) { this.posterPath = posterPath; }

    public String getBackdropPath() { return backdropPath; }
    public void setBackdropPath(String backdropPath) { this.backdropPath = backdropPath; }

    public Integer getRuntime() { return runtime; }
    public void setRuntime(Integer runtime) { this.runtime = runtime; }

    public Set<Genre> getGenres() { return genres; }
    public void setGenres(Set<Genre> genres) { this.genres = genres; }

    public Double getMoodScore() { return moodScore; }
    public void setMoodScore(Double moodScore) { this.moodScore = moodScore; }

    public Double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(Double relevanceScore) { this.relevanceScore = relevanceScore; }

    public Map<String, Double> getMoodScores() { return moodScores; }
    public void setMoodScores(Map<String, Double> moodScores) { this.moodScores = moodScores; }
}
