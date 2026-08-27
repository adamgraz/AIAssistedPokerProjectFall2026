import { cardLabel, isRedSuit } from "./cards";

export function Card({ card, faceDown = false }) {
  if (faceDown) {
    return <div className="card card-back" />;
  }
  return (
    <div className={`card ${isRedSuit(card) ? "red" : "black"}`}>
      {cardLabel(card)}
    </div>
  );
}
