import React, { useState, useEffect, useRef, useCallback } from 'react';

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
  const [suggestions, setSuggestions] = useState([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [aiMode, setAiMode] = useState(false);
  const debounceRef = useRef(null);
  const containerRef = useRef(null);

  const fetchSuggestions = useCallback((q) => {
    if (q.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    fetch(`${API_BASE}/suggest?q=${encodeURIComponent(q.trim())}`)
      .then(res => res.json())
      .then(data => {
        setSuggestions(data);
        setShowSuggestions(data.length > 0);
        setActiveSuggestion(-1);
      })
      .catch(() => setSuggestions([]));
  }, []);

  const handleQueryChange = (e) => {
    const val = e.target.value;
    setQuery(val);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => fetchSuggestions(val), 200);
  };

  const handleSuggestionClick = (suggestion) => {
    setQuery(suggestion.title);
    setShowSuggestions(false);
    onSearch(selectedMood, suggestion.title, false, suggestion.id);
  };

  const handleKeyDown = (e) => {
    if (!showSuggestions || suggestions.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveSuggestion(prev => Math.min(prev + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveSuggestion(prev => Math.max(prev - 1, -1));
    } else if (e.key === 'Enter' && activeSuggestion >= 0) {
      e.preventDefault();
      handleSuggestionClick(suggestions[activeSuggestion]);
    } else if (e.key === 'Escape') {
      setShowSuggestions(false);
    }
  };

  // Close suggestions on outside click
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setShowSuggestions(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

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
    onSearch(newMood, query, false, null);
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    setShowSuggestions(false);
    onSearch(selectedMood, query, aiMode, null);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.heading}>How are you feeling?</h2>
      <p style={styles.subheading}>Pick a mood, search by title, or try AI-powered natural language search</p>

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
        <button
          type="button"
          onClick={() => setAiMode(!aiMode)}
          style={{
            ...styles.aiToggle,
            ...(aiMode ? styles.aiToggleActive : {}),
          }}
          title={aiMode ? 'AI search enabled — describe what you want' : 'Click to enable AI-powered search'}
        >
          {'\u{2728}'} AI
        </button>
        <div ref={containerRef} style={styles.inputWrapper}>
          <input
            type="text"
            value={query}
            onChange={handleQueryChange}
            onKeyDown={handleKeyDown}
            onFocus={() => suggestions.length > 0 && !aiMode && setShowSuggestions(true)}
            placeholder={aiMode ? 'Describe what you want... e.g. "scary movies for halloween"' : 'Search by title...'}
            style={{
              ...styles.searchInput,
              ...(aiMode ? styles.searchInputAi : {}),
            }}
            autoComplete="off"
          />
          {showSuggestions && suggestions.length > 0 && (
            <div style={styles.suggestionsDropdown}>
              {suggestions.map((s, i) => (
                <div
                  key={s.id}
                  onClick={() => handleSuggestionClick(s)}
                  style={{
                    ...styles.suggestionItem,
                    ...(i === activeSuggestion ? styles.suggestionItemActive : {}),
                  }}
                  onMouseEnter={() => setActiveSuggestion(i)}
                >
                  <span style={styles.suggestionTitle}>{s.title}</span>
                  {s.year && <span style={styles.suggestionYear}>({s.year})</span>}
                </div>
              ))}
            </div>
          )}
        </div>
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
  inputWrapper: {
    flex: 1,
    position: 'relative',
  },
  searchInput: {
    width: '100%',
    padding: '12px 18px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '12px',
    color: '#e0e0e0',
    fontSize: '0.95rem',
    fontFamily: 'Inter, sans-serif',
    outline: 'none',
    transition: 'border-color 0.2s',
    boxSizing: 'border-box',
  },
  suggestionsDropdown: {
    position: 'absolute',
    top: '100%',
    left: 0,
    right: 0,
    marginTop: '4px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '10px',
    overflow: 'hidden',
    zIndex: 10,
    boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
  },
  suggestionItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '10px 16px',
    cursor: 'pointer',
    transition: 'background 0.15s',
  },
  suggestionItemActive: {
    background: '#1a2744',
  },
  suggestionTitle: {
    color: '#e0e0e0',
    fontSize: '0.9rem',
    fontFamily: 'Inter, sans-serif',
  },
  suggestionYear: {
    color: '#5a6480',
    fontSize: '0.8rem',
    fontFamily: 'Inter, sans-serif',
  },
  aiToggle: {
    padding: '12px 14px',
    background: '#16213e',
    border: '1px solid #2a2a4a',
    borderRadius: '12px',
    color: '#8892b0',
    fontSize: '0.85rem',
    fontWeight: 600,
    cursor: 'pointer',
    fontFamily: 'Inter, sans-serif',
    transition: 'all 0.2s ease',
    whiteSpace: 'nowrap',
    flexShrink: 0,
  },
  aiToggleActive: {
    background: 'rgba(224, 64, 251, 0.15)',
    borderColor: '#e040fb',
    color: '#e040fb',
    boxShadow: '0 0 12px rgba(224, 64, 251, 0.3)',
  },
  searchInputAi: {
    borderColor: '#e040fb55',
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
