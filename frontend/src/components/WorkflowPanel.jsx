import React, { useRef, useEffect } from 'react';

const TYPE_COLORS = {
  API_GATEWAY: '#40c4ff',
  CACHE_CHECK: '#ffab00',
  CACHE_WRITE: '#ffab00',
  GRAPH_DB_QUERY: '#ab47bc',
  EXTERNAL_API: '#42a5f5',
  RANKING: '#66bb6a',
  CIRCUIT_BREAKER: '#ef5350',
  RATE_LIMIT: '#ffa726',
  RESPONSE: '#00c853',
  ERROR: '#ff5252',
};

const TYPE_ICONS = {
  API_GATEWAY: '\u{1F6AA}',
  CACHE_CHECK: '\u{1F4BE}',
  CACHE_WRITE: '\u{1F4BE}',
  GRAPH_DB_QUERY: '\u{1F5C3}\u{FE0F}',
  EXTERNAL_API: '\u{1F310}',
  RANKING: '\u{1F4CA}',
  CIRCUIT_BREAKER: '\u{26A1}',
  RATE_LIMIT: '\u{23F1}\u{FE0F}',
  RESPONSE: '\u{2705}',
  ERROR: '\u{274C}',
};

function WorkflowPanel({ events, connected }) {
  const scrollRef = useRef(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [events]);

  // Group events by traceId
  const groupedEvents = [];
  let currentGroup = null;

  for (const event of events) {
    if (event.type === 'REQUEST_START') {
      currentGroup = {
        traceId: event.traceId,
        method: event.method,
        path: event.path,
        timestamp: event.timestamp,
        steps: [],
      };
      groupedEvents.push(currentGroup);
    } else if (event.type === 'WORKFLOW_STEP' && currentGroup) {
      currentGroup.steps.push(event);
    } else if (event.type === 'WORKFLOW_STEP') {
      // Step without a request start — create a new implicit group
      if (!currentGroup || currentGroup.traceId !== event.traceId) {
        currentGroup = {
          traceId: event.traceId,
          method: '?',
          path: '?',
          timestamp: event.timestamp,
          steps: [],
        };
        groupedEvents.push(currentGroup);
      }
      currentGroup.steps.push(event);
    }
  }

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h3 style={styles.title}>
          <span style={styles.titleIcon}>{'\u{1F527}'}</span>
          System Design Panel
        </h3>
        <span style={styles.subtitle}>Live workflow visualization</span>
      </div>

      <div style={styles.legend}>
        {Object.entries({
          'Gateway': '#40c4ff',
          'Cache': '#ffab00',
          'Graph DB': '#ab47bc',
          'External API': '#42a5f5',
          'Circuit Breaker': '#ef5350',
          'Rate Limit': '#ffa726',
          'Ranking': '#66bb6a',
          'Response': '#00c853',
        }).map(([label, color]) => (
          <span key={label} style={styles.legendItem}>
            <span style={{ ...styles.legendDot, background: color }} />
            {label}
          </span>
        ))}
      </div>

      <div ref={scrollRef} style={styles.eventList}>
        {groupedEvents.length === 0 ? (
          <div style={styles.emptyState}>
            <p style={styles.emptyIcon}>{'\u{1F4E1}'}</p>
            <p style={styles.emptyText}>Waiting for requests...</p>
            <p style={styles.emptyHint}>Search for a movie to see the workflow</p>
          </div>
        ) : (
          groupedEvents.map((group, gi) => (
            <div key={gi} style={styles.requestGroup}>
              <div style={styles.requestHeader}>
                <span style={styles.requestMethod}>{group.method}</span>
                <span style={styles.requestPath}>{group.path}</span>
                <span style={styles.traceId}>#{group.traceId}</span>
              </div>
              <div style={styles.requestDivider} />
              {group.steps.map((step, si) => (
                <WorkflowStepRow key={si} step={step} isLast={si === group.steps.length - 1} />
              ))}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function StatusCodeBadge({ code }) {
  if (!code) return null;
  const badgeColor = code < 300 ? '#00c853' : code < 500 ? '#ffab00' : '#ff5252';
  return (
    <span style={{
      ...styles.badge,
      background: badgeColor + '22',
      color: badgeColor,
      borderColor: badgeColor + '55',
      marginLeft: '6px',
    }}>
      {code}
    </span>
  );
}

function MetadataPills({ metadata }) {
  if (!metadata || Object.keys(metadata).length === 0) return null;
  return (
    <div style={styles.metadataRow}>
      {Object.entries(metadata).map(([key, value]) => (
        <span key={key} style={styles.metadataPill}>
          <span style={styles.metadataKey}>{key}:</span> {value}
        </span>
      ))}
    </div>
  );
}

function WorkflowStepRow({ step, isLast }) {
  const color = TYPE_COLORS[step.type] || '#8892b0';
  const icon = TYPE_ICONS[step.type] || '\u{25CF}';
  const isCacheHit = step.cacheStatus === 'HIT';
  const isCacheMiss = step.cacheStatus === 'MISS';

  return (
    <div style={styles.stepRow}>
      <div style={styles.timeline}>
        <div style={{ ...styles.timelineDot, borderColor: color, background: isLast ? color : 'transparent' }} />
        {!isLast && <div style={{ ...styles.timelineLine, background: color + '40' }} />}
      </div>

      <div style={styles.stepContent}>
        <div style={styles.stepHeader}>
          <span style={styles.stepIcon}>{icon}</span>
          <span style={{ ...styles.stepName, color }}>[{step.stepNumber}] {step.name}</span>
          {step.type === 'RESPONSE' && <StatusCodeBadge code={step.statusCode} />}
          <span style={styles.stepDuration}>{step.durationMs}ms</span>
        </div>

        <div style={styles.stepDetail}>
          {step.detail}
        </div>

        <div style={styles.badgeRow}>
          {isCacheHit && (
            <span style={{ ...styles.badge, background: 'rgba(0, 200, 83, 0.15)', color: '#00c853', borderColor: 'rgba(0, 200, 83, 0.3)' }}>
              HIT
            </span>
          )}
          {isCacheMiss && (
            <span style={{ ...styles.badge, background: 'rgba(255, 171, 0, 0.15)', color: '#ffab00', borderColor: 'rgba(255, 171, 0, 0.3)' }}>
              MISS
            </span>
          )}
          {step.resultCount != null && step.type !== 'RESPONSE' && (
            <span style={{ ...styles.badge, background: 'rgba(171, 71, 188, 0.15)', color: '#ab47bc', borderColor: 'rgba(171, 71, 188, 0.3)' }}>
              {'\u{2192}'} {step.resultCount} results
            </span>
          )}
        </div>

        <MetadataPills metadata={step.metadata} />

        {step.query && (
          <div style={styles.queryBlock}>
            <code style={styles.queryCode}>{step.query.substring(0, 300)}{step.query.length > 300 ? '...' : ''}</code>
          </div>
        )}

        {step.type === 'RESPONSE' && (
          <div style={styles.totalTime}>
            Total: <strong>{step.durationMs}ms</strong>
          </div>
        )}
      </div>
    </div>
  );
}

const styles = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
  },
  header: {
    padding: '16px 18px 12px',
    borderBottom: '1px solid #2a2a4a',
  },
  title: {
    fontSize: '1rem',
    fontWeight: 600,
    color: '#e0e0e0',
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    marginBottom: '2px',
  },
  titleIcon: {
    fontSize: '1.1rem',
  },
  subtitle: {
    fontSize: '0.75rem',
    color: '#5a6480',
  },
  legend: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '12px',
    padding: '10px 18px',
    borderBottom: '1px solid #1a1a2e',
    fontSize: '0.7rem',
    color: '#8892b0',
  },
  legendItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
  },
  legendDot: {
    width: '6px',
    height: '6px',
    borderRadius: '50%',
    display: 'inline-block',
  },
  eventList: {
    flex: 1,
    overflowY: 'auto',
    padding: '12px',
  },
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '60px 20px',
    textAlign: 'center',
  },
  emptyIcon: {
    fontSize: '2rem',
    marginBottom: '12px',
  },
  emptyText: {
    color: '#8892b0',
    fontSize: '0.9rem',
    marginBottom: '4px',
  },
  emptyHint: {
    color: '#5a6480',
    fontSize: '0.8rem',
  },
  requestGroup: {
    background: '#111122',
    borderRadius: '10px',
    border: '1px solid #2a2a4a',
    marginBottom: '12px',
    overflow: 'hidden',
  },
  requestHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '10px 14px',
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.75rem',
  },
  requestMethod: {
    fontWeight: 700,
    color: '#40c4ff',
  },
  requestPath: {
    color: '#e0e0e0',
    flex: 1,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  traceId: {
    color: '#5a6480',
    fontSize: '0.7rem',
  },
  requestDivider: {
    height: '1px',
    background: '#2a2a4a',
  },
  stepRow: {
    display: 'flex',
    padding: '0 14px',
  },
  timeline: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    width: '20px',
    marginRight: '10px',
    paddingTop: '14px',
  },
  timelineDot: {
    width: '10px',
    height: '10px',
    borderRadius: '50%',
    border: '2px solid',
    flexShrink: 0,
  },
  timelineLine: {
    width: '2px',
    flex: 1,
    marginTop: '2px',
    minHeight: '10px',
  },
  stepContent: {
    flex: 1,
    padding: '10px 0',
    borderBottom: '1px solid #1a1a2e',
    minWidth: 0,
  },
  stepHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    marginBottom: '3px',
  },
  stepIcon: {
    fontSize: '0.8rem',
    flexShrink: 0,
  },
  stepName: {
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.75rem',
    fontWeight: 600,
    flex: 1,
  },
  stepDuration: {
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.7rem',
    color: '#5a6480',
    flexShrink: 0,
  },
  stepDetail: {
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.7rem',
    color: '#8892b0',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    lineHeight: '1.4',
  },
  badgeRow: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '4px',
    marginTop: '4px',
  },
  badge: {
    display: 'inline-block',
    padding: '1px 6px',
    borderRadius: '4px',
    fontSize: '0.65rem',
    fontWeight: 700,
    fontFamily: "'JetBrains Mono', monospace",
    border: '1px solid',
  },
  metadataRow: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '4px',
    marginTop: '5px',
  },
  metadataPill: {
    display: 'inline-block',
    padding: '2px 7px',
    borderRadius: '10px',
    fontSize: '0.6rem',
    fontFamily: "'JetBrains Mono', monospace",
    background: 'rgba(136, 146, 176, 0.1)',
    color: '#8892b0',
    border: '1px solid rgba(136, 146, 176, 0.2)',
  },
  metadataKey: {
    color: '#40c4ff',
    fontWeight: 600,
  },
  queryBlock: {
    marginTop: '6px',
    padding: '6px 8px',
    background: '#0a0a14',
    borderRadius: '4px',
    overflow: 'hidden',
  },
  queryCode: {
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.65rem',
    color: '#ab47bc',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
  },
  totalTime: {
    marginTop: '6px',
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: '0.75rem',
    color: '#00c853',
  },
};

export default WorkflowPanel;
