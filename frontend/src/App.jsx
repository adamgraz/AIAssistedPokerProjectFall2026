import { useState } from "react";
import { useGameSocket } from "./useGameSocket";
import { Card } from "./Card";
import { Seat } from "./Seat";
import "./App.css";

function App() {
  const { connected, playerId, table, lastError, send } = useGameSocket();
  const [displayName, setDisplayName] = useState("Player");
  const [buyIn, setBuyIn] = useState(1000);
  const [betAmount, setBetAmount] = useState(20);

  const round = table?.round ?? null;
  const mySeat = table?.seats.find((s) => s.player?.id === playerId) ?? null;
  const isSeated = mySeat !== null;
  const isMyTurn = round !== null && mySeat !== null && round.actingSeat === mySeat.index;
  const canAct = connected && isMyTurn;
  const occupiedSeats = table?.seats.filter((s) => s.player !== null) ?? [];

  return (
    <div className="page">
      <h1>Poker</h1>
      <p className="conn-status">
        {connected ? "connected" : "disconnected"}
        {playerId && ` — ${playerId.slice(0, 8)}`}
      </p>
      {lastError && <p className="error">{lastError}</p>}

      <div className="felt">
        <div className="board">
          {round?.board.map((card, i) => <Card key={i} card={card} />)}
          {!round && <span className="felt-hint">Waiting for a hand to start…</span>}
        </div>
        {round && <div className="pot">Pot: {round.pot}</div>}
        {isMyTurn && <div className="turn-banner">Your turn</div>}
      </div>

      <div className="seats">
        {occupiedSeats.map((seat) => (
          <Seat
            key={seat.index}
            seat={seat}
            table={table}
            round={round}
            isYou={seat.player.id === playerId}
            isActing={round?.actingSeat === seat.index}
          />
        ))}
      </div>

      {!isSeated && (
        <div className="controls">
          <h2>Sit down</h2>
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          <input
            type="number"
            value={buyIn}
            onChange={(e) => setBuyIn(Number(e.target.value))}
          />
          <button disabled={!connected} onClick={() => send("SIT_DOWN", { displayName, amount: buyIn })}>
            Sit down
          </button>
        </div>
      )}

      {isSeated && round && (
        <div className="controls">
          <h2>Actions</h2>
          <button disabled={!canAct} onClick={() => send("FOLD")}>Fold</button>
          <button disabled={!canAct} onClick={() => send("CHECK")}>Check</button>
          <button disabled={!canAct} onClick={() => send("CALL")}>Call</button>
          <button disabled={!canAct} onClick={() => send("BET", { amount: betAmount })}>
            Bet
          </button>
          <button disabled={!canAct} onClick={() => send("RAISE", { amount: betAmount })}>
            Raise
          </button>
          <button disabled={!canAct} onClick={() => send("ALL_IN")}>All in</button>
          <input
            type="number"
            value={betAmount}
            onChange={(e) => setBetAmount(Number(e.target.value))}
          />
        </div>
      )}
    </div>
  );
}

export default App;
