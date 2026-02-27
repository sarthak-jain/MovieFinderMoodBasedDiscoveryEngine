import React, { useState, useCallback } from 'react';
import SearchPanel from './components/SearchPanel';
import MovieGrid from './components/MovieGrid';
import MovieDetail from './components/MovieDetail';
import WorkflowPanel from './components/WorkflowPanel';
import { useWorkflowStream } from './hooks/useWorkflowStream';
import './App.css';

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';

function App() {
  const [movies, setMovies] = useState([]);
  const [selectedMovie, setSelectedMovie] = useState(null);
  const [loading, setLoading] = useState(false);
  const [searchInfo, setSearchInfo] = useState(null);
  const { events, clearEvents, connected } = useWorkflowStream();

  const handleSearch = useCallback(async (mood, query) => {
    setLoading(true);
    setSelectedMovie(null);
    clearEvents();

    try {
      const params = new URLSearchParams();
      if (mood) params.set('mood', mood);
      if (query) params.set('query', query);

      const response = await fetch(`${API_BASE}/search?${params}`);
      const data = await response.json();

      setMovies(data.movies || []);
      setSearchInfo({
        mood: data.mood,
        query: data.query,
        totalResults: data.totalResults,
        searchTimeMs: data.searchTimeMs
      });
    } catch (err) {
      console.error('Search failed:', err);
      setMovies([]);
    } finally {
      setLoading(false);
    }
  }, [clearEvents]);

  const handleMovieSelect = useCallback(async (movie) => {
    clearEvents();
    try {
      const response = await fetch(`${API_BASE}/movie/${movie.id}`);
      const data = await response.json();
      setSelectedMovie(data);
    } catch (err) {
      console.error('Failed to fetch movie details:', err);
      setSelectedMovie(movie);
    }
  }, [clearEvents]);

  const handleBack = useCallback(() => {
    setSelectedMovie(null);
  }, []);

  return (
    <div className="app">
      <header className="app-header">
        <h1 className="app-title">MovieFinder</h1>
        <span className="app-subtitle">Mood-based Discovery Engine</span>
        <div className="connection-status">
          <span className={`status-dot ${connected ? 'connected' : 'disconnected'}`} />
          {connected ? 'Live' : 'Disconnected'}
        </div>
      </header>

      <div className="app-layout">
        <div className="main-panel">
          {selectedMovie ? (
            <MovieDetail movie={selectedMovie} onBack={handleBack} />
          ) : (
            <>
              <SearchPanel onSearch={handleSearch} loading={loading} />
              {searchInfo && (
                <div className="search-info">
                  Found {searchInfo.totalResults} results
                  {searchInfo.mood && <> for mood: <strong>{searchInfo.mood}</strong></>}
                  {searchInfo.query && <> matching "<strong>{searchInfo.query}</strong>"</>}
                  <span className="search-time"> ({searchInfo.searchTimeMs}ms)</span>
                </div>
              )}
              <MovieGrid movies={movies} onMovieSelect={handleMovieSelect} loading={loading} />
            </>
          )}
        </div>

        <div className="workflow-panel-container">
          <WorkflowPanel events={events} connected={connected} />
        </div>
      </div>
    </div>
  );
}

export default App;
