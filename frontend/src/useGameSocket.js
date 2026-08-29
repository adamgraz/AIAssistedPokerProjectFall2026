import { useEffect, useRef, useState } from "react";

const WS_URL = "ws://localhost:7070/ws";

// One WebSocket connection for the whole app's lifetime. React's dev-mode double-mount
// would otherwise open two sockets - the ref guards against that, not just cleanliness.
export function useGameSocket() {
  const socketRef = useRef(null);
  const [connected, setConnected] = useState(false);
  const [playerId, setPlayerId] = useState(null);
  const [table, setTable] = useState(null);
  const [lastError, setLastError] = useState(null);
  const [availableModes, setAvailableModes] = useState([]);

  useEffect(() => {
    const socket = new WebSocket(WS_URL);
    socketRef.current = socket;

    // StrictMode's dev-only double-mount opens a throwaway socket, then this same effect
    // runs again for real. The throwaway's close event fires asynchronously and can arrive
    // *after* the real socket has already connected - without this check, that stale event
    // clobbers `connected` back to false and it never recovers. Every handler below only
    // acts if `socket` is still the one currently in socketRef.
    const isCurrent = () => socketRef.current === socket;

    socket.onopen = () => { if (isCurrent()) setConnected(true); };
    socket.onclose = () => { if (isCurrent()) setConnected(false); };

    socket.onmessage = (event) => {
      if (!isCurrent()) return;
      const envelope = JSON.parse(event.data);
      // Not shown in the UI - inspect the wire payload via DevTools console instead.
      console.log("[ws]", envelope.type, envelope.payload);
      switch (envelope.type) {
        case "WELCOME":
          setPlayerId(envelope.payload.playerId);
          setAvailableModes(envelope.payload.availableModes ?? []);
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

    return () => socket.close();
  }, []);

  function send(type, payload = {}) {
    if (socketRef.current?.readyState !== WebSocket.OPEN) {
      console.warn("[ws] cannot send, socket isn't open:", type);
      return;
    }
    socketRef.current.send(JSON.stringify({ type, payload }));
  }

  return { connected, playerId, table, lastError, send, availableModes };
}
