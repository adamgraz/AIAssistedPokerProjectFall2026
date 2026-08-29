const RANK_ORDER = [
  "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT",
  "NINE", "TEN", "JACK", "QUEEN", "KING", "ACE",
];

const RANK_LABEL = {
  TWO: "2", THREE: "3", FOUR: "4", FIVE: "5", SIX: "6", SEVEN: "7", EIGHT: "8",
  NINE: "9", TEN: "10", JACK: "J", QUEEN: "Q", KING: "K", ACE: "A",
};

const SUIT_SYMBOL = { CLUBS: "♣", DIAMONDS: "♦", HEARTS: "♥", SPADES: "♠" };
const RED_SUITS = new Set(["DIAMONDS", "HEARTS"]);

export function cardLabel(card) {
  return `${RANK_LABEL[card.rank]}${SUIT_SYMBOL[card.suit]}`;
}

export function isRedSuit(card) {
  return RED_SUITS.has(card.suit);
}

export function rankValue(card) {
  return RANK_ORDER.indexOf(card.rank);
}

export function sortHighToLow(cards) {
  return [...cards].sort((a, b) => rankValue(b) - rankValue(a));
}
