import React, { useState, useEffect } from 'react';

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';

const MOOD_EMOJIS = {
  'cozy': '\u{1F9E3}',
  'dark': '\u{1F480}',
  'mind-bending': '\u{1F9E0}',
  'feel-good': '\u{2600}\u{FE0F}',
  'adrenaline': '\u{1F525}',
  'romantic': '\u{2764}\u{FE0F}',
  'nostalgic': '\u{2B50}',
  'thought-provoking': '\u{1F4A1}'
};

function SearchPanel({ onSearch, loading }) {
  const [moods, setMoods] = useState([]);
  const [selectedMood, setSelectedMood] = useState(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    fetch(`${API_BASE}/moods`)
      .then(res => res.json())
      .then(data => setMoods(data))
      .catch(err => {
        console.error('Failed to fetch moods:', err);
        // Fallback moods
        setMoods([
          { name: 'cozy', description: 'Warm, comforting films' },
          { name: 'dark', description: 'Dark, gritty, intense' },
          { name: 'mind-bending', description: 'Reality-twisting' },
          { name: 'feel-good', description: 'Uplifting stories' },
          { name: 'adrenaline', description: 'Heart-pounding action' },
          { name: 'romantic', description: 'Love stories' },
          { name: 'nostalgic', description: 'Classic favorites' },
          { name: 'thought-provoking', description: 'Makes you think' }
        ]);
      });
  }, []);

  const handleMoodClick = (moodName) => {
    const newMood = selectedMood === moodName ? null : moodName;
    setSelectedMood(newMood);
    onSearch(newMood, query);
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    onSearch(selectedMood, query);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.heading}>How are you feeling?</h2>
      <p style={styles.subheading}>Pick a mood or search by title</p>

      <div style={styles.moodGrid}>
        {moods.map(mood => (
          <button
            key={mood.name}
            onClick={() => handleMoodClick(mood.name)}
            style={{
              ...styles.moodChip,
              ...(selectedMood === mood.name ? styles.moodChipActive : {})
            }}
            title={mood.description}
          >
            <span style={styles.moodEmoji}>{MOOD_EMOJIS[mood.name] || '\u{1F3AC}'}</span>
            <span style={styles.moodName}>{mood.name}</span>
          </button>
        ))}
      </div>

      <form onSubmit={handleSubmit} style={styles.searchForm}>
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by title..."
          style={styles.searchInput}
        />
        <button type="submit" style={styles.searchButton} disabled={loading}>
          {loading ? 'Searching...' : 'Search'}
        </button>
      </form>
    </div>
  );
}

const styles = {
  container: {
    marginBottom: '24px',
  },
  heading: {
    fontSize: '1.5rem',
    fontWeight: 600,
    marginBottom: '4px',
    color: '#e0e0e0',
  },
  subheading: {
    fontSize: '0.875rem',
    color: '#8892b0',
    marginBottom: '20px',
  },
  moodGrid: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '10px',
    marginBottom: '20px',
  },
  moodChip: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '10px 18px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '24px',
    color: '#e0e0e0',
    fontSize: '0.875rem',
    fontWeight: 500,
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    fontFamily: 'Inter, sans-serif',
  },
  moodChipActive: {
    background: '#6c63ff',
    borderColor: '#6c63ff',
    color: '#ffffff',
    boxShadow: '0 0 16px rgba(108, 99, 255, 0.4)',
  },
  moodEmoji: {
    fontSize: '1.1rem',
  },
  moodName: {
    textTransform: 'capitalize',
  },
  searchForm: {
    display: 'flex',
    gap: '12px',
  },
  searchInput: {
    flex: 1,
    padding: '12px 18px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '12px',
    color: '#e0e0e0',
    fontSize: '0.95rem',
    fontFamily: 'Inter, sans-serif',
    outline: 'none',
    transition: 'border-color 0.2s',
  },
  searchButton: {
    padding: '12px 28px',
    background: '#6c63ff',
    border: 'none',
    borderRadius: '12px',
    color: '#ffffff',
    fontSize: '0.95rem',
    fontWeight: 600,
    cursor: 'pointer',
    fontFamily: 'Inter, sans-serif',
    transition: 'background 0.2s',
  },
};

export default SearchPanel;
