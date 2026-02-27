import { useState, useEffect, useCallback, useRef } from 'react';

const SSE_URL = process.env.REACT_APP_API_URL
  ? `${process.env.REACT_APP_API_URL}/workflow/stream`
  : 'http://localhost:8080/api/workflow/stream';

export function useWorkflowStream() {
  const [events, setEvents] = useState([]);
  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);

  const connect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    const eventSource = new EventSource(SSE_URL);
    eventSourceRef.current = eventSource;

    eventSource.onopen = () => {
      setConnected(true);
    };

    eventSource.addEventListener('request-start', (e) => {
      try {
        const data = JSON.parse(e.data);
        setEvents(prev => [...prev, {
          ...data,
          eventType: 'REQUEST_START',
        }]);
      } catch (err) {
        console.error('Failed to parse request-start event:', err);
      }
    });

    eventSource.addEventListener('workflow-step', (e) => {
      try {
        const data = JSON.parse(e.data);
        setEvents(prev => [...prev, {
          ...data,
          eventType: 'WORKFLOW_STEP',
        }]);
      } catch (err) {
        console.error('Failed to parse workflow-step event:', err);
      }
    });

    eventSource.onerror = () => {
      setConnected(false);
      eventSource.close();

      // Reconnect after 3 seconds
      reconnectTimeoutRef.current = setTimeout(() => {
        connect();
      }, 3000);
    };
  }, []);

  useEffect(() => {
    connect();

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
    };
  }, [connect]);

  const clearEvents = useCallback(() => {
    setEvents([]);
  }, []);

  return { events, clearEvents, connected };
}
