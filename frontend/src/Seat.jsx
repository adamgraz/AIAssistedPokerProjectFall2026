import { Card } from "./Card";

export function Seat({ seat, table, round, isYou, isActing }) {
  const player = seat.player;
  const isDealer = table.dealerSeat === seat.index;
  const isSmallBlind = round?.smallBlindSeat === seat.index;
  const isBigBlind = round?.bigBlindSeat === seat.index;

  return (
    <div className={`seat ${isActing ? "acting" : ""} ${isYou ? "you" : ""}`}>
      <div className="seat-header">
        <span className="seat-name">{player.displayName}{isYou ? " (you)" : ""}</span>
        <span className="seat-tags">
          {isDealer && <span className="tag tag-dealer">D</span>}
          {isSmallBlind && <span className="tag tag-blind">SB</span>}
          {isBigBlind && <span className="tag tag-blind">BB</span>}
        </span>
      </div>
      <div className="seat-stack">{player.stack} chips</div>
      <div className="seat-status">{player.status}</div>
      {isYou && round && round.yourHoleCards.length > 0 && (
        <div className="hole-cards">
          {round.yourHoleCards.map((card, i) => (
            <Card key={i} card={card} />
          ))}
        </div>
      )}
    </div>
  );
}
