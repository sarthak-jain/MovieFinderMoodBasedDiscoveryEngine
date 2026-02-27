package com.moviefinder.model;

import java.util.List;

public class SearchResult {

    private List<Movie> movies;
    private int totalResults;
    private int page;
    private int totalPages;
    private String mood;
    private String query;
    private long searchTimeMs;

    public SearchResult() {}

    public SearchResult(List<Movie> movies, int totalResults, int page, String mood, String query, long searchTimeMs) {
        this.movies = movies;
        this.totalResults = totalResults;
        this.page = page;
        this.totalPages = (int) Math.ceil((double) totalResults / 20);
        this.mood = mood;
        this.query = query;
        this.searchTimeMs = searchTimeMs;
    }

    public List<Movie> getMovies() { return movies; }
    public void setMovies(List<Movie> movies) { this.movies = movies; }

    public int getTotalResults() { return totalResults; }
    public void setTotalResults(int totalResults) { this.totalResults = totalResults; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public long getSearchTimeMs() { return searchTimeMs; }
    public void setSearchTimeMs(long searchTimeMs) { this.searchTimeMs = searchTimeMs; }
}
