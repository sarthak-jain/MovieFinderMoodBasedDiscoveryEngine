import React from 'react';

const PLACEHOLDER_POSTER = 'data:image/svg+xml,' + encodeURIComponent(`
  <svg xmlns="http://www.w3.org/2000/svg" width="300" height="450" viewBox="0 0 300 450">
    <rect fill="#16213e" width="300" height="450"/>
    <text fill="#5a6480" font-family="sans-serif" font-size="18" text-anchor="middle" x="150" y="220">No Poster</text>
    <text fill="#5a6480" font-family="sans-serif" font-size="40" text-anchor="middle" x="150" y="180">🎬</text>
  </svg>
`);

function MovieGrid({ movies, onMovieSelect, loading }) {
  if (loading) {
    return (
      <div style={styles.loadingContainer}>
        <div style={styles.spinner} />
        <p style={styles.loadingText}>Searching movies...</p>
      </div>
    );
  }

  if (movies.length === 0) {
    return (
      <div style={styles.emptyState}>
        <p style={styles.emptyIcon}>🎬</p>
        <p style={styles.emptyText}>Pick a mood or search for a movie to get started</p>
      </div>
    );
  }

  return (
    <div style={styles.grid}>
      {movies.map(movie => (
        <div
          key={movie.id || movie.tmdbId}
          style={styles.card}
          onClick={() => onMovieSelect(movie)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => e.key === 'Enter' && onMovieSelect(movie)}
        >
          <div style={styles.posterContainer}>
            <img
              src={movie.posterPath || PLACEHOLDER_POSTER}
              alt={movie.title}
              style={styles.poster}
              onError={(e) => { e.target.src = PLACEHOLDER_POSTER; }}
            />
            {movie.avgRating && (
              <div style={styles.rating}>
                <span style={styles.star}>&#9733;</span>
                {movie.avgRating.toFixed(1)}
              </div>
            )}
            {movie.moodScore && (
              <div style={styles.moodBadge}>
                {Math.round(movie.moodScore * 100)}% match
              </div>
            )}
          </div>
          <div style={styles.info}>
            <h3 style={styles.title}>{movie.title}</h3>
            <span style={styles.year}>{movie.year}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

const styles = {
  grid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))',
    gap: '20px',
  },
  card: {
    background: '#16213e',
    borderRadius: '12px',
    overflow: 'hidden',
    cursor: 'pointer',
    transition: 'transform 0.2s ease, box-shadow 0.2s ease',
    border: '1px solid #2a2a4a',
  },
  posterContainer: {
    position: 'relative',
    aspectRatio: '2/3',
    overflow: 'hidden',
  },
  poster: {
    width: '100%',
    height: '100%',
    objectFit: 'cover',
    display: 'block',
  },
  rating: {
    position: 'absolute',
    top: '8px',
    right: '8px',
    background: 'rgba(0,0,0,0.75)',
    padding: '4px 8px',
    borderRadius: '6px',
    fontSize: '0.8rem',
    fontWeight: 600,
    color: '#ffab00',
    backdropFilter: 'blur(4px)',
  },
  star: {
    marginRight: '3px',
  },
  moodBadge: {
    position: 'absolute',
    bottom: '8px',
    left: '8px',
    background: 'rgba(108, 99, 255, 0.85)',
    padding: '3px 8px',
    borderRadius: '6px',
    fontSize: '0.7rem',
    fontWeight: 600,
    color: '#fff',
    backdropFilter: 'blur(4px)',
  },
  info: {
    padding: '12px',
  },
  title: {
    fontSize: '0.9rem',
    fontWeight: 600,
    color: '#e0e0e0',
    marginBottom: '4px',
    lineHeight: 1.3,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  year: {
    fontSize: '0.8rem',
    color: '#8892b0',
  },
  loadingContainer: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '60px',
  },
  spinner: {
    width: '40px',
    height: '40px',
    border: '3px solid #2a2a4a',
    borderTop: '3px solid #6c63ff',
    borderRadius: '50%',
    animation: 'spin 0.8s linear infinite',
  },
  loadingText: {
    marginTop: '16px',
    color: '#8892b0',
    fontSize: '0.9rem',
  },
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '80px 20px',
  },
  emptyIcon: {
    fontSize: '3rem',
    marginBottom: '16px',
  },
  emptyText: {
    color: '#5a6480',
    fontSize: '1rem',
    textAlign: 'center',
  },
};

// Inject keyframes for spinner
const styleSheet = document.createElement('style');
styleSheet.textContent = `@keyframes spin { to { transform: rotate(360deg); } }`;
document.head.appendChild(styleSheet);

export default MovieGrid;
