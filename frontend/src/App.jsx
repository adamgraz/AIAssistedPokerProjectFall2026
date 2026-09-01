import { useEffect, useRef, useState } from "react";
import { useGameSocket } from "./useGameSocket";
import { Card } from "./Card";
import { Seat } from "./Seat";
import { FinalStatsModal } from "./FinalStatsModal";
import "./App.css";

const MODE_LABELS = {
  TEXAS_HOLDEM: "Texas Hold'em",
  OMAHA: "Omaha",
  TEXAS_BOMB_POT: "Texas Bomb Pot",
  OMAHA_BOMB_POT: "Omaha Bomb Pot",
};
const modeLabel = (mode) => MODE_LABELS[mode] ?? mode;
const LIVE_STAGES = ["PREFLOP", "FLOP", "TURN", "RIVER"];
// Must match PokerServer.BOMB_POT_OPT_IN_TIMEOUT_SECONDS - not sent over the wire since this
// countdown is cosmetic only (see the effect below), so there's no single source of truth to
// read it from instead. Keep the two in sync by hand if either one ever changes.
const BOMB_POT_OPT_IN_SECONDS = 60;

function App() {
  const { connected, reconnectFailed, playerId, table, lastError, send, availableModes, profile } = useGameSocket();
  const [displayName, setDisplayName] = useState("Player");
  const [buyIn, setBuyIn] = useState(1000);
  const [betAmount, setBetAmount] = useState(20);
  const [passphrase, setPassphrase] = useState("");
  const [showCreateProfile, setShowCreateProfile] = useState(false);
  const [newDisplayName, setNewDisplayName] = useState("");

  // Logging in fills in the sit-down name for free - still just a starting point, the field
  // stays editable same as it always was for guests.
  useEffect(() => {
    if (profile) setDisplayName(profile.displayName);
  }, [profile]);

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
  const minRaiseTo = (round?.currentBet ?? 0) + (round?.lastRaiseSize ?? 0);
  const isBustedSittingOut = mySeat?.player.status === "SITTING_OUT" && mySeat?.player.stack === 0;
  const occupiedSeats = table?.seats.filter((s) => s.player !== null) ?? [];
  const notVoted = occupiedSeats.map((s) => s.player).filter((p) => !table?.votes?.[p.id]);
  const votingOpen = table?.votingOpen ?? false;
  const bombPotOptInOpen = table?.bombPotOptInOpen ?? false;
  const bombPotOptIns = table?.bombPotOptIns ?? {};
  const notAnswered = occupiedSeats.map((s) => s.player).filter((p) => bombPotOptIns[p.id] === undefined);
  const optedIn = occupiedSeats.map((s) => s.player).filter((p) => bombPotOptIns[p.id] === true);
  const optedOut = occupiedSeats.map((s) => s.player).filter((p) => bombPotOptIns[p.id] === false);
  // No hand ever auto-starts, including the first - someone has to trigger it, same NEXT_HAND
  // flow as every later hand, so the whole table has a chance to sit down first.
  const canStartHand =
    isSeated && occupiedSeats.length >= 2 && !votingOpen && !bombPotOptInOpen && (round === null || isComplete);

  // Server doesn't send a deadline - this is a cosmetic countdown only, starting the moment
  // this client first sees the window open. The server's own 60s timeout is authoritative
  // regardless of what this shows.
  const [optInSecondsLeft, setOptInSecondsLeft] = useState(BOMB_POT_OPT_IN_SECONDS);
  useEffect(() => {
    if (!bombPotOptInOpen) return;
    setOptInSecondsLeft(BOMB_POT_OPT_IN_SECONDS);
    const start = Date.now();
    const interval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - start) / 1000);
      setOptInSecondsLeft(Math.max(0, BOMB_POT_OPT_IN_SECONDS - elapsed));
    }, 1000);
    return () => clearInterval(interval);
  }, [bombPotOptInOpen]);

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
      {round && !votingOpen && !bombPotOptInOpen && (
        <div className="round-banner-top">Round of {modeLabel(table.variant)}</div>
      )}
      <h1>Poker</h1>
      <p className="conn-status">
        {connected ? "connected" : reconnectFailed ? "connection lost — refresh to retry" : "reconnecting…"}
        {playerId && ` — ${playerId.slice(0, 8)}`}
      </p>
      {lastError && <p className="error">{lastError}</p>}
      {isSeated && (
        <button className="leave-button" disabled={!connected} onClick={() => send("LEAVE_TABLE")}>
          Leave table
        </button>
      )}

      <div className="felt">
        <div className="boards">
          {round?.boards.map((board, i) => (
            <div key={i} className="board">
              {board.map((card, j) => <Card key={j} card={card} />)}
            </div>
          ))}
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
            hideLastHandCards={bombPotOptInOpen}
            canKick={isSeated}
            onKick={(targetId) => send("REMOVE_PLAYER", { targetPlayerId: targetId })}
          />
        ))}
      </div>

      {canStartHand && (
        <div className="controls">
          <button onClick={() => send("NEXT_HAND")}>{round === null ? "Start hand" : "Next hand"}</button>
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

      {isSeated && bombPotOptInOpen && (
        <div className="mode-vote">
          <h2>Opt into {modeLabel(table.pendingBombPotVariant)}?</h2>
          <p className="opt-in-timer">Auto opt-out in {optInSecondsLeft}s</p>
          <div className="opt-in-buttons">
            <button
              className={bombPotOptIns[playerId] === true ? "mode-option voted" : "mode-option"}
              onClick={() => send("BOMB_POT_OPT", { amount: 1 })}
            >
              Opt in ({optedIn.length})
            </button>
            <button
              className={bombPotOptIns[playerId] === false ? "mode-option voted" : "mode-option"}
              onClick={() => send("BOMB_POT_OPT", { amount: 0 })}
            >
              Opt out ({optedOut.length})
            </button>
          </div>
          {notAnswered.length > 0 && (
            <div className="not-voted">
              <span>Haven't answered:</span>
              <ul>
                {notAnswered.map((p) => <li key={p.id}>{p.displayName}</li>)}
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

      {!isSeated && !profile && (
        <div className="controls login-panel">
          <h2>Log in (optional - guest play works without it)</h2>
          <input
            type="password"
            placeholder="Passphrase"
            value={passphrase}
            onChange={(e) => setPassphrase(e.target.value)}
          />
          <button
            disabled={!connected || !passphrase}
            onClick={() => send("LOGIN", { passphrase })}
          >
            Log in
          </button>
          <button className="link-button" onClick={() => setShowCreateProfile((v) => !v)}>
            {showCreateProfile ? "Cancel" : "New here? Create a profile"}
          </button>
          {showCreateProfile && (
            <div className="create-profile-row">
              <input
                placeholder="Display name"
                value={newDisplayName}
                onChange={(e) => setNewDisplayName(e.target.value)}
              />
              <button
                disabled={!connected || !passphrase || !newDisplayName}
                onClick={() => send("CREATE_PROFILE", { passphrase, displayName: newDisplayName })}
              >
                Create profile
              </button>
            </div>
          )}
        </div>
      )}

      {profile && !isSeated && (
        <p className="profile-status">
          Logged in as <strong>{profile.displayName}</strong> — {profile.handsPlayed} hands played,
          net {profile.netChips >= 0 ? "+" : ""}{profile.netChips} chips
        </p>
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

      {isSeated && isLiveBettingStage && round.currentBet > 0 && (
        <div className="bet-info">
          <div className="bet-info-row">Total to call: {round.currentBet}</div>
          <div className="bet-info-row">You owe: {owed}</div>
          <div className="bet-info-row">Min raise to: {minRaiseTo}</div>
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
