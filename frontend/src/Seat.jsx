import { Card } from "./Card";
import { sortHighToLow } from "./cards";

const ACTION_LABELS = { FOLD: "Folded", CHECK: "Checked", CALL: "Called", BET: "Bet", RAISE: "Raised", ALL_IN: "All in" };

export function Seat({
  seat,
  table,
  round,
  isYou,
  isActing,
  isComplete,
  canKick,
  onKick,
  hideLastHandCards,
  cardsVisible,
  onToggleCardsVisible,
}) {
  const player = seat.player;
  const isDealer = table.dealerSeat === seat.index;
  const isSmallBlind = round?.smallBlindSeat === seat.index;
  const isBigBlind = round?.bigBlindSeat === seat.index;

  // Your own hole cards are always visible to you; everyone else's are only ever revealed
  // once the hand reaches COMPLETE and they didn't fold (round.revealedHoleCards). During a
  // bomb pot's opt-in window, currentRound is still last hand's COMPLETE round - hiding these
  // avoids the last hand's cards bleeding into an opt-in decision that's about the next one.
  const holeCards = hideLastHandCards
    ? []
    : isYou ? round?.yourHoleCards ?? [] : round?.revealedHoleCards?.[player.id] ?? [];
  // Only populated at a real showdown - never for an uncontested fold win. One entry per
  // board (just one for every variant except a bomb pot's double-board format). HandEvaluator
  // returns each board's cards in whatever order the winning 5-card combination happened to
  // land in, so sort for display - high to low reads as "the hand" the way a person would lay it out.
  const bestFiveByBoard = isComplete && !hideLastHandCards
    ? (round?.bestFiveByBoard ?? []).map((board) => sortHighToLow(board[player.id] ?? []))
    : [];
  const winnersByBoard = round?.winnersByBoard ?? [];
  const isWinner = isComplete && !hideLastHandCards && winnersByBoard.some((winners) => winners.includes(player.id));
  // Cleared server-side at the start of every street, so this only ever reflects the
  // CURRENT street's action - never a stale leftover from an earlier one.
  const lastAction = !isComplete && !hideLastHandCards ? round?.lastActionByPlayer?.[player.id] : undefined;

  return (
    <div className={`seat ${isActing ? "acting" : ""} ${isYou ? "you" : ""}`}>
      <div className="seat-header">
        <span className="seat-name">
          {isWinner && <span className="winner-crown" title="Won the pot">👑</span>}
          {player.displayName}{isYou ? " (you)" : ""}
        </span>
        <span className="seat-tags">
          {isDealer && <span className="tag tag-dealer">D</span>}
          {isSmallBlind && <span className="tag tag-blind">SB</span>}
          {isBigBlind && <span className="tag tag-blind">BB</span>}
          {!player.connected && <span className="tag tag-disconnected">disconnected</span>}
        </span>
      </div>
      <div className="seat-stack">{player.stack} chips</div>
      <div className="seat-status">{player.status}</div>
      {lastAction && <div className="seat-last-action">{ACTION_LABELS[lastAction] ?? lastAction}</div>}
      {!isYou && !player.connected && canKick && (
        <button className="kick-button" onClick={() => onKick(player.id)}>Remove seat</button>
      )}
      {holeCards.length > 0 && (
        <div className="hole-cards">
          {holeCards.map((card, i) => (
            <Card key={i} card={card} faceDown={isYou && !cardsVisible} />
          ))}
        </div>
      )}
      {isYou && holeCards.length > 0 && (
        <button className="toggle-cards-button" onClick={onToggleCardsVisible}>
          {cardsVisible ? "Hide cards" : "Show cards"}
        </button>
      )}
      {bestFiveByBoard.map((bestFive, boardIndex) => bestFive.length > 0 && (
        <div key={boardIndex} className="best-five">
          <span className="best-five-label">
            Best hand{bestFiveByBoard.length > 1 ? ` (Board ${String.fromCharCode(65 + boardIndex)})` : ""}
          </span>
          <div className="hole-cards">
            {bestFive.map((card, i) => (
              <Card key={i} card={card} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
