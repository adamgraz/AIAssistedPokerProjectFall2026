import { useEffect, useRef, useState } from "react";
import type { Envelope, TableSnapshot } from "./protocol";

const WS_URL = "ws://localhost:7070/ws";

// One WebSocket connection for the whole app's lifetime. React's dev-mode double-mount
// would otherwise open two sockets - the ref guards against that, not just cleanliness.
export function useGameSocket() {
  const socketRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const [playerId, setPlayerId] = useState<string | null>(null);
  const [table, setTable] = useState<TableSnapshot | null>(null);
  const [lastError, setLastError] = useState<string | null>(null);

  useEffect(() => {
    const socket = new WebSocket(WS_URL);
    socketRef.current = socket;

    socket.onopen = () => setConnected(true);
    socket.onclose = () => setConnected(false);

    socket.onmessage = (event) => {
      const envelope: Envelope = JSON.parse(event.data);
      switch (envelope.type) {
        case "WELCOME":
          setPlayerId((envelope.payload as { playerId: string }).playerId);
          break;
        case "STATE":
          setTable(envelope.payload as TableSnapshot);
          break;
        case "ERROR":
          setLastError((envelope.payload as { message: string }).message);
          break;
      }
    };

    return () => socket.close();
  }, []);

  function send(type: string, payload: Record<string, unknown> = {}) {
    socketRef.current?.send(JSON.stringify({ type, payload }));
  }

  return { connected, playerId, table, lastError, send };
}
