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

  useEffect(() => {
    const socket = new WebSocket(WS_URL);
    socketRef.current = socket;

    socket.onopen = () => setConnected(true);
    socket.onclose = () => setConnected(false);

    socket.onmessage = (event) => {
      const envelope = JSON.parse(event.data);
      switch (envelope.type) {
        case "WELCOME":
          setPlayerId(envelope.payload.playerId);
          break;
        case "STATE":
          setTable(envelope.payload);
          break;
        case "ERROR":
          setLastError(envelope.payload.message);
          break;
      }
    };

    return () => socket.close();
  }, []);

  function send(type, payload = {}) {
    socketRef.current?.send(JSON.stringify({ type, payload }));
  }

  return { connected, playerId, table, lastError, send };
}
