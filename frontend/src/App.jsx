import { useState } from "react";
import { useGameSocket } from "./useGameSocket";
import "./App.css";

// First-pass wiring, not a real table UI yet: proves the socket round-trip (connect, sit
// down, send an action, see broadcast state) with a raw JSON dump instead of rendered seats.
function App() {
  const { connected, playerId, table, lastError, send } = useGameSocket();
  const [displayName, setDisplayName] = useState("Player");
  const [buyIn, setBuyIn] = useState(1000);
  const [actionAmount, setActionAmount] = useState(0);

  return (
    <div style={{ fontFamily: "monospace", padding: "1.5rem", maxWidth: 700 }}>
      <h1>Poker - wire test</h1>
      <p>connected: {String(connected)}</p>
      <p>your playerId: {playerId ?? "(waiting for WELCOME)"}</p>
      {lastError && <p style={{ color: "crimson" }}>last error: {lastError}</p>}

      <h2>Sit down</h2>
      <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
      <input
        type="number"
        value={buyIn}
        onChange={(e) => setBuyIn(Number(e.target.value))}
      />
      <button onClick={() => send("SIT_DOWN", { displayName, amount: buyIn })}>
        Sit down
      </button>

      <h2>Send an action</h2>
      {["FOLD", "CHECK", "CALL", "BET", "RAISE", "ALL_IN"].map((type) => (
        <button key={type} onClick={() => send(type, { amount: actionAmount })}>
          {type}
        </button>
      ))}
      <br />
      amount:{" "}
      <input
        type="number"
        value={actionAmount}
        onChange={(e) => setActionAmount(Number(e.target.value))}
      />

      <h2>Table state</h2>
      <pre>{JSON.stringify(table, null, 2)}</pre>
    </div>
  );
}

export default App;
