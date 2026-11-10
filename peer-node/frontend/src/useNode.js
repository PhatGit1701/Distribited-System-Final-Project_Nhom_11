import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { request } from './api.mjs';
import { initialState, reducer } from './model.mjs';
import { subscribeToNodeEvents } from './eventStream.mjs';

export function useNode() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const [session, setSession] = useState(null);
  const [initializing, setInitializing] = useState(true);
  const [connection, setConnection] = useState('offline');
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState('');
  const syncRef = useRef(async () => {});

  const handleError = useCallback((error) => {
    if (error.status === 401) {
      setSession(null);
      dispatch({ type: 'RESET' });
      setNotice({ kind: 'error', text: 'Your node session has expired. Sign in to reconnect.' });
    } else setNotice({ kind: 'error', text: error.message || 'The node could not be reached.' });
  }, []);

  useEffect(() => {
    let active = true;
    const restore = async () => {
      const results = await Promise.allSettled([request('/status'), request('/session')]);
      if (!active) return;
      if (results[0].status === 'fulfilled') dispatch({ type: 'NODE', node: results[0].value });
      else setNotice({ kind: 'error', text: 'The local node is unavailable. Check that the peer process is running.' });
      if (results[1].status === 'fulfilled') {
        setSession(results[1].value);
        dispatch({ type: 'NODE', node: results[1].value.node });
      }
      setInitializing(false);
    };
    restore();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const update = () => request('/status', { signal: controller.signal })
      .then(node => dispatch({ type: 'NODE', node }))
      .catch(() => {});
    const timer = setInterval(update, 8000);
    return () => { clearInterval(timer); controller.abort(); };
  }, []);

  useEffect(() => {
    if (!session?.csrfToken) { setConnection('offline'); return; }
    let active = true;
    let generation = 0;
    let socketReady = false;
    const controller = new AbortController();
    const synchronize = async () => {
      const current = ++generation;
      dispatch({ type: 'SYNC_START', generation: current });
      if (socketReady) setConnection('syncing');
      try {
        const snapshot = await request('/snapshot', { signal: controller.signal });
        if (!active || current !== generation) return;
        dispatch({ type: 'SNAPSHOT', snapshot, generation: current });
        if (socketReady) setConnection('live');
      } catch (error) {
        if (active && error.name !== 'AbortError') handleError(error);
      }
    };
    syncRef.current = synchronize;
    setConnection('connecting');
    const client = new Client({
      webSocketFactory: () => new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`),
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      connectionTimeout: 8000,
      onConnect: () => {
        if (!active) return;
        socketReady = false;
        subscribeToNodeEvents(client, {
          isActive: () => active,
          onSync: () => {
            // SYNC is sent after the broker has registered SUBSCRIBE. Only now
            // can a snapshot and the following buffered events close the gap.
            socketReady = true;
            synchronize();
          },
          onEvent: event => dispatch({ type: 'EVENT', event, generation }),
          onError: () => setNotice({ kind: 'error', text: 'An unreadable node event was received. Refresh the snapshot to resync.' }),
        });
      },
      onWebSocketClose: () => {
        if (active) { socketReady = false; setConnection('reconnecting'); }
      },
      onStompError: () => {
        if (active) setConnection('reconnecting');
      },
    });
    synchronize();
    client.activate();
    return () => {
      active = false;
      controller.abort();
      syncRef.current = async () => {};
      client.deactivate();
    };
  }, [session?.csrfToken, handleError]);

  const authenticate = async (credentials) => {
    setBusy('session');
    try {
      const value = await request('/session', { method: 'POST', body: credentials });
      dispatch({ type: 'RESET', node: value.node });
      setSession(value);
      setNotice({ kind: 'success', text: `Connected as ${value.node.username || credentials.username}.` });
      return true;
    } catch (error) {
      setNotice({ kind: 'error', text: error.message });
      return false;
    } finally { setBusy(''); }
  };

  const refresh = useCallback(async () => {
    await syncRef.current();
  }, []);

  const mutate = async (key, path, method, body, success) => {
    setBusy(key);
    try {
      const result = await request(path, { method, body, token: session?.csrfToken });
      if (success) setNotice({ kind: 'success', text: success });
      await refresh();
      return result;
    } catch (error) {
      handleError(error);
      return null;
    } finally { setBusy(''); }
  };

  const publish = async file => {
    const form = new FormData();
    form.append('file', file);
    return mutate('publish', '/publish', 'POST', form, `Published ${file.name} to the tracker.`);
  };

  const logout = async () => {
    setBusy('session');
    try {
      await request('/session', { method: 'DELETE', token: session?.csrfToken });
      setSession(null);
      dispatch({ type: 'RESET' });
      setNotice({ kind: 'success', text: 'Browser session disconnected. The peer node continues running.' });
    } catch (error) { handleError(error); }
    finally { setBusy(''); }
  };

  return {
    state, session, initializing, connection, notice, busy, authenticate, refresh, publish, logout,
    dismissNotice: () => setNotice(null),
    startDownload: fileId => mutate(fileId, '/downloads', 'POST', { fileId }),
    cancelDownload: fileId => mutate(fileId, `/downloads/${encodeURIComponent(fileId)}`, 'DELETE'),
    handleError,
  };
}
