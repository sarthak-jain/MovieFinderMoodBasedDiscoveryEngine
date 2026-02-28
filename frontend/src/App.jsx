import React, { useState, useCallback } from 'react';
import SearchPanel from './components/SearchPanel';
import MovieGrid from './components/MovieGrid';
import MovieDetail from './components/MovieDetail';
import WorkflowPanel from './components/WorkflowPanel';
import { useWorkflowStream } from './hooks/useWorkflowStream';
import './App.css';

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';

function Pagination({ currentPage, totalResults, pageSize, maxPages, onPageChange }) {
  const totalPages = Math.min(Math.ceil(totalResults / pageSize), maxPages);
  if (totalPages <= 1) return null;

  const pages = [];
  for (let i = 0; i < totalPages; i++) pages.push(i);

  return (
    <div style={paginationStyles.container}>
      <button
        onClick={() => onPageChange(currentPage - 1)}
        disabled={currentPage === 0}
        style={{
          ...paginationStyles.button,
          ...(currentPage === 0 ? paginationStyles.disabled : {}),
        }}
      >
        Prev
      </button>
      {pages.map(p => (
        <button
          key={p}
          onClick={() => onPageChange(p)}
          style={{
            ...paginationStyles.button,
            ...(p === currentPage ? paginationStyles.active : {}),
          }}
        >
          {p + 1}
        </button>
      ))}
      <button
        onClick={() => onPageChange(currentPage + 1)}
        disabled={currentPage >= totalPages - 1}
        style={{
          ...paginationStyles.button,
          ...(currentPage >= totalPages - 1 ? paginationStyles.disabled : {}),
        }}
      >
        Next
      </button>
    </div>
  );
}

const paginationStyles = {
  container: {
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    gap: '8px',
    marginTop: '32px',
    paddingBottom: '24px',
  },
  button: {
    padding: '8px 14px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '8px',
    color: '#e0e0e0',
    fontSize: '0.85rem',
    fontWeight: 500,
    cursor: 'pointer',
    fontFamily: 'Inter, sans-serif',
    transition: 'all 0.2s ease',
  },
  active: {
    background: '#6c63ff',
    borderColor: '#6c63ff',
    color: '#ffffff',
  },
  disabled: {
    opacity: 0.4,
    cursor: 'not-allowed',
  },
};

function App() {
  const [movies, setMovies] = useState([]);
  const [selectedMovie, setSelectedMovie] = useState(null);
  const [loading, setLoading] = useState(false);
  const [searchInfo, setSearchInfo] = useState(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [lastSearch, setLastSearch] = useState({ mood: null, query: null });
  const { events, clearEvents, connected } = useWorkflowStream();

  const doSearch = useCallback(async (mood, query, page) => {
    setLoading(true);
    setSelectedMovie(null);
    clearEvents();

    try {
      const params = new URLSearchParams();
      if (mood) params.set('mood', mood);
      if (query) params.set('query', query);
      params.set('page', page);

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

  const handleSearch = useCallback((mood, query) => {
    setCurrentPage(0);
    setLastSearch({ mood, query });
    doSearch(mood, query, 0);
  }, [doSearch]);

  const handlePageChange = useCallback((page) => {
    setCurrentPage(page);
    doSearch(lastSearch.mood, lastSearch.query, page);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [doSearch, lastSearch]);

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

  const handleLogoClick = useCallback(() => {
    setSelectedMovie(null);
    setMovies([]);
    setSearchInfo(null);
    setCurrentPage(0);
    setLastSearch({ mood: null, query: null });
    clearEvents();
  }, [clearEvents]);

  return (
    <div className="app">
      <header className="app-header">
        <h1 className="app-title" onClick={handleLogoClick} style={{ cursor: 'pointer' }}>MovieFinder</h1>
        <span className="app-subtitle">Mood-based Discovery Engine</span>
        <div className="connection-status">
          <span className={`status-dot ${connected ? 'connected' : 'disconnected'}`} />
          {connected ? 'Live' : 'Disconnected'}
        </div>
      </header>

      <div className="app-layout">
        <div className="main-panel">
          {selectedMovie ? (
            <MovieDetail movie={selectedMovie} onBack={handleBack} onMovieSelect={handleMovieSelect} />
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
              {searchInfo && searchInfo.totalResults > 20 && (
                <Pagination
                  currentPage={currentPage}
                  totalResults={searchInfo.totalResults}
                  pageSize={20}
                  maxPages={10}
                  onPageChange={handlePageChange}
                />
              )}
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
