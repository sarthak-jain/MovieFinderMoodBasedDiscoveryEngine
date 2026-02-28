package com.moviefinder.model;

import com.moviefinder.service.AiSearchService;

public class AiSearchResponse {

    private AiSearchService.AiSearchResult interpretation;
    private SearchResult results;

    public AiSearchResponse() {}

    public AiSearchResponse(AiSearchService.AiSearchResult interpretation, SearchResult results) {
        this.interpretation = interpretation;
        this.results = results;
    }

    public AiSearchService.AiSearchResult getInterpretation() { return interpretation; }
    public void setInterpretation(AiSearchService.AiSearchResult interpretation) { this.interpretation = interpretation; }

    public SearchResult getResults() { return results; }
    public void setResults(SearchResult results) { this.results = results; }
}
