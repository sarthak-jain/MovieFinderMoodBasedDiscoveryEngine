import React, { useState, useEffect } from 'react';

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';

const PLACEHOLDER_POSTER = 'data:image/svg+xml,' + encodeURIComponent(`
  <svg xmlns="http://www.w3.org/2000/svg" width="300" height="450" viewBox="0 0 300 450">
    <rect fill="#16213e" width="300" height="450"/>
    <text fill="#5a6480" font-family="sans-serif" font-size="18" text-anchor="middle" x="150" y="220">No Poster</text>
  </svg>
`);

function MovieDetail({ movie, onBack }) {
  const [similarMovies, setSimilarMovies] = useState([]);

  useEffect(() => {
    if (movie?.id) {
      fetch(`${API_BASE}/movie/${movie.id}/similar`)
        .then(res => res.json())
        .then(data => setSimilarMovies(data))
        .catch(err => console.error('Failed to fetch similar movies:', err));
    }
  }, [movie?.id]);

  if (!movie) return null;

  const genres = movie.genres || [];
  const moods = (movie.moods || []).filter(m => m.mood);
  const cast = (movie.cast || []).filter(c => c.name).slice(0, 8);
  const directors = movie.directors || [];

  return (
    <div style={styles.container}>
      <button onClick={onBack} style={styles.backButton}>
        &#8592; Back to results
      </button>

      <div style={styles.hero}>
        {movie.backdropPath && (
          <div style={{
            ...styles.backdrop,
            backgroundImage: `url(${movie.backdropPath})`
          }} />
        )}

        <div style={styles.heroContent}>
          <img
            src={movie.posterPath || PLACEHOLDER_POSTER}
            alt={movie.title}
            style={styles.poster}
            onError={(e) => { e.target.src = PLACEHOLDER_POSTER; }}
          />

          <div style={styles.details}>
            <h1 style={styles.title}>{movie.title}</h1>

            <div style={styles.meta}>
              {movie.year && <span style={styles.metaItem}>{movie.year}</span>}
              {movie.runtime && <span style={styles.metaItem}>{movie.runtime} min</span>}
              {movie.avgRating && (
                <span style={styles.ratingBadge}>
                  &#9733; {typeof movie.avgRating === 'number' ? movie.avgRating.toFixed(1) : movie.avgRating}
                </span>
              )}
            </div>

            {movie.tagline && (
              <p style={styles.tagline}>"{movie.tagline}"</p>
            )}

            {genres.length > 0 && (
              <div style={styles.tags}>
                {genres.map((genre, i) => (
                  <span key={i} style={styles.genreTag}>
                    {typeof genre === 'string' ? genre : genre.name}
                  </span>
                ))}
              </div>
            )}

            {moods.length > 0 && (
              <div style={styles.tags}>
                {moods.map((m, i) => (
                  <span key={i} style={styles.moodTag}>
                    {m.mood} {m.score ? `(${Math.round(m.score * 100)}%)` : ''}
                  </span>
                ))}
              </div>
            )}

            {movie.overview && (
              <p style={styles.overview}>{movie.overview}</p>
            )}

            {directors.length > 0 && (
              <div style={styles.section}>
                <span style={styles.sectionLabel}>Directed by:</span>
                <span style={styles.sectionValue}>{directors.join(', ')}</span>
              </div>
            )}

            {cast.length > 0 && (
              <div style={styles.section}>
                <span style={styles.sectionLabel}>Cast:</span>
                <span style={styles.sectionValue}>
                  {cast.map(c => c.role ? `${c.name} as ${c.role}` : c.name).join(', ')}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>

      {similarMovies.length > 0 && (
        <div style={styles.similarSection}>
          <h2 style={styles.similarHeading}>Similar Movies</h2>
          <div style={styles.similarGrid}>
            {similarMovies.map(m => (
              <div key={m.id || m.tmdbId} style={styles.similarCard}>
                <img
                  src={m.posterPath || PLACEHOLDER_POSTER}
                  alt={m.title}
                  style={styles.similarPoster}
                  onError={(e) => { e.target.src = PLACEHOLDER_POSTER; }}
                />
                <div style={styles.similarInfo}>
                  <p style={styles.similarTitle}>{m.title}</p>
                  <p style={styles.similarYear}>{m.year}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {},
  backButton: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    padding: '8px 16px',
    background: 'none',
    border: '1px solid #2a2a4a',
    borderRadius: '8px',
    color: '#8892b0',
    fontSize: '0.875rem',
    cursor: 'pointer',
    marginBottom: '20px',
    fontFamily: 'Inter, sans-serif',
  },
  hero: {
    position: 'relative',
    background: '#16213e',
    borderRadius: '16px',
    overflow: 'hidden',
    marginBottom: '24px',
  },
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundSize: 'cover',
    backgroundPosition: 'center',
    opacity: 0.15,
    filter: 'blur(2px)',
  },
  heroContent: {
    position: 'relative',
    display: 'flex',
    gap: '24px',
    padding: '24px',
  },
  poster: {
    width: '220px',
    minWidth: '220px',
    borderRadius: '12px',
    boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
  },
  details: {
    flex: 1,
  },
  title: {
    fontSize: '1.75rem',
    fontWeight: 700,
    marginBottom: '8px',
    color: '#e0e0e0',
  },
  meta: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    marginBottom: '12px',
  },
  metaItem: {
    fontSize: '0.9rem',
    color: '#8892b0',
  },
  ratingBadge: {
    padding: '4px 10px',
    background: 'rgba(255, 171, 0, 0.15)',
    borderRadius: '6px',
    fontSize: '0.9rem',
    fontWeight: 600,
    color: '#ffab00',
  },
  tagline: {
    fontStyle: 'italic',
    color: '#8892b0',
    fontSize: '0.95rem',
    marginBottom: '12px',
  },
  tags: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '8px',
    marginBottom: '12px',
  },
  genreTag: {
    padding: '4px 12px',
    background: 'rgba(108, 99, 255, 0.15)',
    border: '1px solid rgba(108, 99, 255, 0.3)',
    borderRadius: '16px',
    fontSize: '0.8rem',
    color: '#a78bfa',
  },
  moodTag: {
    padding: '4px 12px',
    background: 'rgba(0, 200, 83, 0.1)',
    border: '1px solid rgba(0, 200, 83, 0.3)',
    borderRadius: '16px',
    fontSize: '0.8rem',
    color: '#69f0ae',
  },
  overview: {
    fontSize: '0.95rem',
    color: '#b0bec5',
    lineHeight: 1.7,
    marginBottom: '16px',
  },
  section: {
    marginBottom: '8px',
    fontSize: '0.875rem',
  },
  sectionLabel: {
    color: '#8892b0',
    marginRight: '8px',
  },
  sectionValue: {
    color: '#e0e0e0',
  },
  similarSection: {
    marginTop: '32px',
  },
  similarHeading: {
    fontSize: '1.25rem',
    fontWeight: 600,
    marginBottom: '16px',
    color: '#e0e0e0',
  },
  similarGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))',
    gap: '16px',
  },
  similarCard: {
    background: '#16213e',
    borderRadius: '10px',
    overflow: 'hidden',
    border: '1px solid #2a2a4a',
  },
  similarPoster: {
    width: '100%',
    aspectRatio: '2/3',
    objectFit: 'cover',
    display: 'block',
  },
  similarInfo: {
    padding: '8px 10px',
  },
  similarTitle: {
    fontSize: '0.8rem',
    fontWeight: 600,
    color: '#e0e0e0',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  similarYear: {
    fontSize: '0.75rem',
    color: '#8892b0',
  },
};

export default MovieDetail;
