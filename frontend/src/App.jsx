import { useEffect, useRef, useState } from "react";
import { useGameSocket } from "./useGameSocket";
import { Card } from "./Card";
import { Seat } from "./Seat";
import { FinalStatsModal } from "./FinalStatsModal";
import "./App.css";

const MODE_LABELS = { TEXAS_HOLDEM: "Texas Hold'em" };
const modeLabel = (mode) => MODE_LABELS[mode] ?? mode;
const LIVE_STAGES = ["PREFLOP", "FLOP", "TURN", "RIVER"];

function App() {
  const { connected, playerId, table, lastError, send, availableModes } = useGameSocket();
  const [displayName, setDisplayName] = useState("Player");
  const [buyIn, setBuyIn] = useState(1000);
  const [betAmount, setBetAmount] = useState(20);

  const round = table?.round ?? null;
  const mySeat = table?.seats.find((s) => s.player?.id === playerId) ?? null;
  const isSeated = mySeat !== null;
  const isLiveBettingStage = round !== null && LIVE_STAGES.includes(round.stage);
  const isComplete = round !== null && round.stage === "COMPLETE";
  const isMyTurn = isLiveBettingStage && mySeat !== null && round.actingSeat === mySeat.index;
  const canAct = connected && isMyTurn;
  const canBet = round?.currentBet === 0;
  const owed = (round?.currentBet ?? 0) - (round?.yourStreetContribution ?? 0);
  const canCheck = canAct && owed === 0;
  const canFold = canAct && owed > 0;
  const isBustedSittingOut = mySeat?.player.status === "SITTING_OUT" && mySeat?.player.stack === 0;
  const occupiedSeats = table?.seats.filter((s) => s.player !== null) ?? [];
  const notVoted = occupiedSeats.map((s) => s.player).filter((p) => !table?.votes?.[p.id]);
  const votingOpen = table?.votingOpen ?? false;

  // Captures this player's own stats on every render while seated, so the moment their seat
  // disappears (LEAVE_TABLE finally applied - possibly deferred until a hand in progress
  // ends) there's still a last-known stack/buy-in to show, even though the seat itself is
  // already gone from the next snapshot.
  const [finalStats, setFinalStats] = useState(null);
  const lastSeatStatsRef = useRef(null);
  const wasSeatedRef = useRef(false);
  useEffect(() => {
    if (isSeated) {
      lastSeatStatsRef.current = { totalBuyIn: mySeat.player.totalBuyIn, stack: mySeat.player.stack };
    } else if (wasSeatedRef.current && lastSeatStatsRef.current) {
      setFinalStats(lastSeatStatsRef.current);
    }
    wasSeatedRef.current = isSeated;
  }, [isSeated, mySeat]);

  return (
    <div className="page">
      {finalStats && (
        <FinalStatsModal stats={finalStats} onContinue={() => setFinalStats(null)} />
      )}
      {isMyTurn && <div className="turn-banner-top">Your turn</div>}
      {round && !votingOpen && (
        <div className="round-banner-top">Round of {modeLabel(table.variant)}</div>
      )}
      <h1>Poker</h1>
      <p className="conn-status">
        {connected ? "connected" : "disconnected"}
        {playerId && ` — ${playerId.slice(0, 8)}`}
      </p>
      {lastError && <p className="error">{lastError}</p>}
      {isSeated && (
        <button className="leave-button" disabled={!connected} onClick={() => send("LEAVE_TABLE")}>
          Leave table
        </button>
      )}

      <div className="felt">
        <div className="board">
          {round?.board.map((card, i) => <Card key={i} card={card} />)}
          {!round && <span className="felt-hint">Waiting for a hand to start…</span>}
        </div>
        {round && <div className="pot">Pot: {round.pot}</div>}
      </div>

      <div className="seats">
        {occupiedSeats.map((seat) => (
          <Seat
            key={seat.index}
            seat={seat}
            table={table}
            round={round}
            isYou={seat.player.id === playerId}
            isActing={isLiveBettingStage && round.actingSeat === seat.index}
            isComplete={isComplete}
            canKick={isSeated}
            onKick={(targetId) => send("REMOVE_PLAYER", { targetPlayerId: targetId })}
          />
        ))}
      </div>

      {isSeated && isComplete && !votingOpen && (
        <div className="controls">
          <button onClick={() => send("NEXT_HAND")}>Next hand</button>
        </div>
      )}

      {isSeated && votingOpen && (
        <div className="mode-vote">
          <h2>Vote: next hand's mode</h2>
          <div className="mode-columns">
            {availableModes.map((mode) => {
              const voters = occupiedSeats
                .map((s) => s.player)
                .filter((p) => table.votes?.[p.id] === mode);
              return (
                <div key={mode} className="mode-column">
                  <button
                    className={table.votes?.[playerId] === mode ? "mode-option voted" : "mode-option"}
                    onClick={() => send("VOTE_GAME_MODE", { mode })}
                  >
                    {modeLabel(mode)}: {voters.length}
                  </button>
                  <ul className="voter-list">
                    {voters.map((p) => <li key={p.id}>{p.displayName}</li>)}
                  </ul>
                </div>
              );
            })}
          </div>
          {notVoted.length > 0 && (
            <div className="not-voted">
              <span>Haven't voted:</span>
              <ul>
                {notVoted.map((p) => <li key={p.id}>{p.displayName}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}

      {isSeated && isBustedSittingOut && (
        <div className="controls">
          <h2>Rebuy</h2>
          <input
            type="number"
            value={buyIn}
            onChange={(e) => setBuyIn(Number(e.target.value))}
          />
          <button disabled={!connected} onClick={() => send("REBUY", { amount: buyIn })}>
            Rebuy
          </button>
        </div>
      )}

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

      {isSeated && isLiveBettingStage && (
        <div className="controls">
          <h2>Actions</h2>
          <button disabled={!canFold} onClick={() => send("FOLD")}>Fold</button>
          <button disabled={!canCheck} onClick={() => send("CHECK")}>Check</button>
          <button disabled={!canAct} onClick={() => send("CALL")}>Call</button>
          {canBet ? (
            <button disabled={!canAct} onClick={() => send("BET", { amount: betAmount })}>
              Bet
            </button>
          ) : (
            <button disabled={!canAct} onClick={() => send("RAISE", { amount: betAmount })}>
              Raise
            </button>
          )}
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
