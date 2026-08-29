import { Card } from "./Card";
import { sortHighToLow } from "./cards";

export function Seat({ seat, table, round, isYou, isActing, isComplete, canKick, onKick }) {
  const player = seat.player;
  const isDealer = table.dealerSeat === seat.index;
  const isSmallBlind = round?.smallBlindSeat === seat.index;
  const isBigBlind = round?.bigBlindSeat === seat.index;

  // Your own hole cards are always visible to you; everyone else's are only ever revealed
  // once the hand reaches COMPLETE and they didn't fold (round.revealedHoleCards).
  const holeCards = isYou ? round?.yourHoleCards ?? [] : round?.revealedHoleCards?.[player.id] ?? [];
  // Only populated at a real showdown - never for an uncontested fold win. HandEvaluator
  // returns these in whatever order the winning 5-card combination happened to land in, so
  // sort for display - high to low reads as "the hand" the way a person would lay it out.
  const bestFive = isComplete ? sortHighToLow(round?.bestFive?.[player.id] ?? []) : [];
  const isWinner = isComplete && (round?.winners ?? []).includes(player.id);

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
      {!isYou && !player.connected && canKick && (
        <button className="kick-button" onClick={() => onKick(player.id)}>Remove seat</button>
      )}
      {holeCards.length > 0 && (
        <div className="hole-cards">
          {holeCards.map((card, i) => (
            <Card key={i} card={card} />
          ))}
        </div>
      )}
      {bestFive.length > 0 && (
        <div className="best-five">
          <span className="best-five-label">Best hand</span>
          <div className="hole-cards">
            {bestFive.map((card, i) => (
              <Card key={i} card={card} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
