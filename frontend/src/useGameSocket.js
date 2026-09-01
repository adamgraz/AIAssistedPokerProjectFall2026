import { useEffect, useRef, useState } from "react";

// Host: derived from wherever the page itself was loaded from, not hardcoded - a phone on
// the LAN loads the page from the host's IP, not "localhost", so the socket has to follow
// that same host or it'd try to reach a server on the phone itself.
// Port: PokerServer.DEFAULT_PORT (7070) as the default, since nothing here can read that
// Java constant at build time - override with VITE_BACKEND_PORT in a .env.local file
// (gitignored) if the backend is ever run on a different port.
const BACKEND_PORT = import.meta.env.VITE_BACKEND_PORT ?? 7070;
const WS_URL = `ws://${window.location.hostname}:${BACKEND_PORT}/ws`;
const RECONNECT_DELAY_MS = 5000;
const MAX_RECONNECT_ATTEMPTS = 5;
const HEARTBEAT_INTERVAL_MS = 30000;

// One WebSocket connection for the whole app's lifetime. React's dev-mode double-mount
// would otherwise open two sockets - the ref guards against that, not just cleanliness.
export function useGameSocket() {
  const socketRef = useRef(null);
  const reconnectAttemptsRef = useRef(0);
  const reconnectTimerRef = useRef(null);
  // Set right before the effect's own cleanup closes the socket on unmount, so that closes
  // onclose from scheduling a reconnect for a connection this hook itself is done with.
  const unmountedRef = useRef(false);
  const [connected, setConnected] = useState(false);
  const [reconnectFailed, setReconnectFailed] = useState(false);
  const [playerId, setPlayerId] = useState(null);
  const [table, setTable] = useState(null);
  const [lastError, setLastError] = useState(null);
  const [availableModes, setAvailableModes] = useState([]);
  // null = playing as a guest (the default, unchanged from before login existed). Set only
  // when a WELCOME carries profile fields - i.e. after a successful LOGIN/CREATE_PROFILE.
  const [profile, setProfile] = useState(null);

  useEffect(() => {
    unmountedRef.current = false;

    function connect() {
      const socket = new WebSocket(WS_URL);
      socketRef.current = socket;

      // StrictMode's dev-only double-mount opens a throwaway socket, then this same effect
      // runs again for real. The throwaway's close event fires asynchronously and can arrive
      // *after* the real socket has already connected - without this check, that stale event
      // clobbers `connected` back to false and it never recovers. Every handler below only
      // acts if `socket` is still the one currently in socketRef.
      const isCurrent = () => socketRef.current === socket;

      // The underlying WebSocket server has its own idle timeout on a quiet connection - with
      // no traffic at all during a long "thinking" pause or gap between hands, it'll drop the
      // socket even though nothing's actually wrong. A no-op ping every 30s keeps it alive.
      let heartbeatTimer = null;

      socket.onopen = () => {
        if (!isCurrent()) return;
        setConnected(true);
        setReconnectFailed(false);
        reconnectAttemptsRef.current = 0;
        heartbeatTimer = setInterval(() => {
          if (socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ type: "PING", payload: {} }));
          }
        }, HEARTBEAT_INTERVAL_MS);
      };

      socket.onclose = () => {
        if (!isCurrent()) return;
        clearInterval(heartbeatTimer);
        setConnected(false);
        if (unmountedRef.current) return;
        // Retry a handful of times, a fixed 5s apart, then give up rather than hammering a
        // server that may just be gone for good - a manual page refresh tries again from zero.
        if (reconnectAttemptsRef.current >= MAX_RECONNECT_ATTEMPTS) {
          setReconnectFailed(true);
          return;
        }
        reconnectAttemptsRef.current += 1;
        reconnectTimerRef.current = setTimeout(connect, RECONNECT_DELAY_MS);
      };

      socket.onmessage = (event) => {
        if (!isCurrent()) return;
        const envelope = JSON.parse(event.data);
        // Not shown in the UI - inspect the wire payload via DevTools console instead.
        console.log("[ws]", envelope.type, envelope.payload);
        switch (envelope.type) {
          case "WELCOME":
            setPlayerId(envelope.payload.playerId);
            setAvailableModes(envelope.payload.availableModes ?? []);
            setProfile(
              envelope.payload.displayName
                ? {
                    displayName: envelope.payload.displayName,
                    handsPlayed: envelope.payload.handsPlayed,
                    netChips: envelope.payload.netChips,
                  }
                : null
            );
            setLastError(null); // a failed LOGIN/CREATE_PROFILE's ERROR shouldn't linger past a later WELCOME
            break;
          case "STATE":
            setTable(envelope.payload);
            // STATE only ever broadcasts after a successful apply() - clear any stale error.
            setLastError(null);
            break;
          case "ERROR":
            setLastError(envelope.payload.message);
            break;
        }
      };
    }

    connect();

    return () => {
      unmountedRef.current = true;
      clearTimeout(reconnectTimerRef.current);
      socketRef.current?.close();
    };
  }, []);

  function send(type, payload = {}) {
    if (socketRef.current?.readyState !== WebSocket.OPEN) {
      console.warn("[ws] cannot send, socket isn't open:", type);
      return;
    }
    socketRef.current.send(JSON.stringify({ type, payload }));
  }

  return { connected, reconnectFailed, playerId, table, lastError, send, availableModes, profile };
}
