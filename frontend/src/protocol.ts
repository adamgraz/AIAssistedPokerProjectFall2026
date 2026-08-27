// Mirrors the Java wire types 1:1 - com.pokerproject.protocol.Envelope/TableSnapshot/etc.
// Kept as plain types, not classes: the backend is the only thing that ever constructs game
// state: the frontend just receives and renders it.

export type Envelope = {
  type: string;
  payload: unknown;
};

export type PlayerSnapshot = {
  id: string;
  displayName: string;
  stack: number;
  status: "ACTIVE" | "FOLDED" | "ALL_IN" | "SITTING_OUT";
};

export type SeatSnapshot = {
  index: number;
  player: PlayerSnapshot | null;
};

export type Card = {
  rank: string;
  suit: string;
};

export type RoundSnapshot = {
  stage: "WAITING" | "PREFLOP" | "FLOP" | "TURN" | "RIVER" | "SHOWDOWN" | "COMPLETE";
  board: Card[];
  pot: number;
  smallBlindSeat: number;
  bigBlindSeat: number;
  actingSeat: number;
  currentBet: number;
  yourHoleCards: Card[];
  revealedHoleCards: Record<string, Card[]>;
};

export type TableSnapshot = {
  seats: SeatSnapshot[];
  dealerSeat: number;
  closed: boolean;
  round: RoundSnapshot | null;
};
