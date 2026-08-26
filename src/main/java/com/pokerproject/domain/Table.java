package com.pokerproject.domain;

import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.TableCommandRequest;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Table {

    private final Seat[] seats;
    private final List<Player> roster = new ArrayList<>();
    private int dealerSeat = -1; // -1 means no hand has been played yet
    private final GameVariant variant;
    private final GameConfig config;
    private GameRound currentRound;
    private boolean closed;

    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> pendingLeaves = new HashSet<>();
    private boolean pendingClose;

    public Table(int seatCount, GameVariant variant, GameConfig config) {
        this.seats = new Seat[seatCount];
        for (int i = 0; i < seatCount; i++) {
            seats[i] = new Seat(i);
        }
        this.variant = variant;
        this.config = config;
    }

    public Seat[] seats() {
        return seats;
    }

    public List<Player> roster() {
        return roster;
    }

    public int dealerSeat() {
        return dealerSeat;
    }

    public GameVariant variant() {
        return variant;
    }

    public GameConfig config() {
        return config;
    }

    public GameRound currentRound() {
        return currentRound;
    }

    public boolean isClosed() {
        return closed;
    }

    public long occupiedSeatCount() {
        long count = 0;
        for (Seat seat : seats) {
            if (!seat.isEmpty()) count++;
        }
        return count;
    }

    // Circular scan skipping any seat that isn't ACTIVE, computed fresh each call so it's
    // always correct against current status with no separate structure to keep in sync.
    public int nextActiveSeat(int from) {
        int n = seats.length;
        for (int step = 1; step <= n; step++) {
            int idx = (from + step) % n;
            Player player = seats[idx].player();
            if (player != null && player.status() == PlayerStatus.ACTIVE) {
                return idx;
            }
        }
        throw new IllegalStateException("no active seat found");
    }

    // === Table commands: sit down, leave, rebuy, end table ===

    public void apply(TableCommandRequest request) {
        switch (request.command()) {
            case SIT_DOWN -> handleSitDown(request);
            case LEAVE_TABLE -> handleLeaveTable(request);
            case REBUY -> handleRebuy(request);
            case END_TABLE -> handleEndTable(request);
        }
    }

    private void handleSitDown(TableCommandRequest request) {
        if (request.amount() <= 0) {
            throw new IllegalStateException("buy-in must be positive");
        }
        int emptySeat = -1;
        for (Seat seat : seats) {
            if (seat.isEmpty()) {
                emptySeat = seat.index();
                break;
            }
        }
        if (emptySeat < 0) {
            throw new IllegalStateException("table is full");
        }
        Player player = new Player(request.playerId(), request.displayName(), request.amount());
        seats[emptySeat].setPlayer(player);
        roster.add(player);

        if (currentRound == null && occupiedSeatCount() >= 2 && !closed) {
            startHand();
        }
    }

    private void handleLeaveTable(TableCommandRequest request) {
        if (isBetweenHands()) {
            removeFromSeat(request.playerId());
        } else {
            pendingLeaves.add(request.playerId());
        }
    }

    private void handleRebuy(TableCommandRequest request) {
        Player player = findPlayer(request.playerId());
        if (player.status() != PlayerStatus.SITTING_OUT || player.stack() != 0) {
            throw new IllegalStateException("rebuy only allowed while sitting out with an empty stack");
        }
        if (request.amount() <= 0) {
            throw new IllegalStateException("rebuy amount must be positive");
        }
        player.rebuy(request.amount());
        player.setStatus(PlayerStatus.ACTIVE);
    }

    private void handleEndTable(TableCommandRequest request) {
        if (isBetweenHands()) {
            closed = true;
            currentRound = null;
        } else {
            pendingClose = true;
        }
    }

    private boolean isBetweenHands() {
        return currentRound == null
                || currentRound.stage() == RoundStage.WAITING
                || currentRound.stage() == RoundStage.COMPLETE;
    }

    private void removeFromSeat(UUID playerId) {
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && p.id().equals(playerId)) {
                seat.setPlayer(null);
                return;
            }
        }
    }

    private Player findPlayer(UUID id) {
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && p.id().equals(id)) {
                return p;
            }
        }
        throw new IllegalStateException("player not seated: " + id);
    }

    // === Hand lifecycle ===

    private void startHand() {
        for (Seat seat : seats) {
            Player player = seat.player();
            if (player != null && player.status() != PlayerStatus.SITTING_OUT) {
                player.setStatus(PlayerStatus.ACTIVE);
            }
        }

        dealerSeat = (dealerSeat < 0) ? firstOccupiedSeatIndex() : nextActiveSeat(dealerSeat);

        GameRound round = new GameRound();
        currentRound = round;
        round.deck().shuffle(random);

        List<Integer> inHand = seatsEligibleToBeDealt();
        int smallBlindSeat;
        int bigBlindSeat;
        if (inHand.size() == 2) {
            smallBlindSeat = dealerSeat;
            bigBlindSeat = nextActiveSeat(dealerSeat);
        } else {
            smallBlindSeat = nextActiveSeat(dealerSeat);
            bigBlindSeat = nextActiveSeat(smallBlindSeat);
        }
        round.setSmallBlindSeat(smallBlindSeat);
        round.setBigBlindSeat(bigBlindSeat);

        postBlind(round, smallBlindSeat, config.smallBlind());
        postBlind(round, bigBlindSeat, config.bigBlind());

        for (int idx : inHand) {
            Player p = seats[idx].player();
            round.holeCards().put(p.id(), new HoleCards(List.of(round.deck().draw(), round.deck().draw())));
        }

        // Guards the same way as dealNextStreet: if both blinds happened to bust a player
        // straight to all-in, nobody's left to act and this value is never actually used.
        int firstToAct = seatsThatCanAct(round).isEmpty() ? bigBlindSeat : nextActiveSeat(bigBlindSeat);
        beginStreet(round, RoundStage.PREFLOP, firstToAct, config.bigBlind(), config.bigBlind());
    }

    private void postBlind(GameRound round, int seatIndex, long amount) {
        Player player = seats[seatIndex].player();
        long post = Math.min(amount, player.stack());
        player.removeFromStack(post);
        round.pot().contribute(player.id(), post);
        long already = round.streetContributions().getOrDefault(player.id(), 0L);
        round.streetContributions().put(player.id(), already + post);
        if (player.stack() == 0) {
            player.setStatus(PlayerStatus.ALL_IN);
        }
    }

    private void beginStreet(GameRound round, RoundStage stage, int firstToAct, long openingBet, long openingMinRaise) {
        round.setStage(stage);
        round.setCurrentBet(openingBet);
        round.setLastRaiseSize(openingMinRaise);
        round.toAct().clear();
        for (int idx : seatsThatCanAct(round)) {
            round.toAct().add(seats[idx].player().id());
        }
        round.setActingSeat(firstToAct);

        if (seatsThatCanAct(round).size() <= 1) {
            round.toAct().clear();
            runItOut(round);
            return;
        }
        if (round.toAct().isEmpty()) {
            advanceToNextStreetOrShowdown(round);
        }
    }

    private void dealNextStreet(GameRound round) {
        RoundStage next = nextStreetAfter(round.stage());
        int cardsToDeal = (next == RoundStage.FLOP) ? 3 : 1;
        for (int i = 0; i < cardsToDeal; i++) {
            round.board().add(round.deck().draw());
        }
        round.streetContributions().clear();
        // If nobody can act (everyone still in the hand is already all-in), there's no
        // "first to act" - beginStreet detects that and runs the rest out; this value is
        // never actually used in that case, so a harmless placeholder is fine.
        int firstToAct = seatsThatCanAct(round).isEmpty() ? dealerSeat : nextActiveSeat(dealerSeat);
        beginStreet(round, next, firstToAct, 0, config.bigBlind());
    }

    private RoundStage nextStreetAfter(RoundStage stage) {
        return switch (stage) {
            case PREFLOP -> RoundStage.FLOP;
            case FLOP -> RoundStage.TURN;
            case TURN -> RoundStage.RIVER;
            default -> throw new IllegalStateException("no next street from " + stage);
        };
    }

    private void advanceToNextStreetOrShowdown(GameRound round) {
        if (round.stage() == RoundStage.RIVER) {
            resolveShowdown(round);
        } else {
            dealNextStreet(round);
        }
    }

    // Betting stopped early because at most one player can still act (everyone else is
    // folded or all-in) - deal every remaining street face-up, then go straight to showdown.
    private void runItOut(GameRound round) {
        while (round.stage() != RoundStage.RIVER) {
            RoundStage next = nextStreetAfter(round.stage());
            int cardsToDeal = (next == RoundStage.FLOP) ? 3 : 1;
            for (int i = 0; i < cardsToDeal; i++) {
                round.board().add(round.deck().draw());
            }
            round.setStage(next);
        }
        resolveShowdown(round);
    }

    // === Betting actions ===

    public void apply(PlayerAction action) {
        if (currentRound == null) {
            throw new IllegalStateException("no round in progress");
        }
        GameRound round = currentRound;
        if (round.stage() == RoundStage.WAITING || round.stage() == RoundStage.SHOWDOWN
                || round.stage() == RoundStage.COMPLETE) {
            throw new IllegalStateException("not an active betting street");
        }

        Player player = seats[round.actingSeat()].player();
        if (player == null || !player.id().equals(action.playerId())) {
            throw new IllegalStateException("not this player's turn");
        }

        long alreadyIn = round.contributionThisStreet(player.id());
        long currentBet = round.currentBet();

        switch (action.type()) {
            case FOLD -> {
                if (alreadyIn >= currentBet) {
                    throw new IllegalStateException("nothing to call - check instead of folding");
                }
                player.setStatus(PlayerStatus.FOLDED);
                round.toAct().remove(player.id());
            }
            case CHECK -> {
                if (alreadyIn != currentBet) {
                    throw new IllegalStateException("cannot check facing a bet");
                }
                round.toAct().remove(player.id());
            }
            case CALL -> {
                long owed = currentBet - alreadyIn;
                long paid = Math.min(owed, player.stack());
                applyChips(round, player, paid);
                if (player.stack() == 0) {
                    player.setStatus(PlayerStatus.ALL_IN);
                }
                round.toAct().remove(player.id());
            }
            case BET -> {
                if (currentBet != 0) {
                    throw new IllegalStateException("a bet is already live - raise instead");
                }
                long betTo = action.amount();
                long maxPossible = player.stack() + alreadyIn;
                if (betTo <= 0 || betTo > maxPossible) {
                    throw new IllegalStateException("invalid bet amount");
                }
                if (betTo < config.bigBlind() && betTo < maxPossible) {
                    throw new IllegalStateException("bet below minimum");
                }
                applyChips(round, player, betTo - alreadyIn);
                round.setCurrentBet(betTo);
                round.setLastRaiseSize(betTo);
                if (player.stack() == 0) {
                    player.setStatus(PlayerStatus.ALL_IN);
                }
                reopenActionExcept(round, player.id());
            }
            case RAISE -> {
                if (currentBet == 0) {
                    throw new IllegalStateException("nothing to raise - bet instead");
                }
                long raiseTo = action.amount();
                long maxPossible = player.stack() + alreadyIn;
                if (raiseTo <= currentBet || raiseTo > maxPossible) {
                    throw new IllegalStateException("invalid raise amount");
                }
                long increment = raiseTo - currentBet;
                if (increment < round.lastRaiseSize() && raiseTo < maxPossible) {
                    throw new IllegalStateException("raise below minimum");
                }
                applyChips(round, player, raiseTo - alreadyIn);
                round.setCurrentBet(raiseTo);
                if (increment >= round.lastRaiseSize()) {
                    round.setLastRaiseSize(increment);
                }
                if (player.stack() == 0) {
                    player.setStatus(PlayerStatus.ALL_IN);
                }
                reopenActionExcept(round, player.id());
            }
            case ALL_IN -> {
                long shove = player.stack();
                long newTotal = alreadyIn + shove;
                applyChips(round, player, shove);
                player.setStatus(PlayerStatus.ALL_IN);
                if (newTotal > currentBet) {
                    long increment = newTotal - currentBet;
                    round.setCurrentBet(newTotal);
                    if (increment >= round.lastRaiseSize()) {
                        round.setLastRaiseSize(increment);
                    }
                    // ponytail: an under-minimum all-in raise still reopens action for every
                    // remaining player here, not just those who haven't acted yet. The exact
                    // casino rule (only reopens for players facing a full raise) is a
                    // deliberate simplification - add it if a real game ever hinges on it.
                    reopenActionExcept(round, player.id());
                } else {
                    round.toAct().remove(player.id());
                }
            }
        }

        if (seatsInHand(round).size() <= 1) {
            awardUncontestedPot(round);
            return;
        }
        if (!round.toAct().isEmpty()) {
            // Someone still owes a decision on the current bet - even if they're the only
            // player left who can act, they haven't been asked yet. Never skip this: beginStreet
            // (reached below once toAct actually empties) is what correctly detects "nobody left
            // who can act" and runs the rest out, but only after every pending decision is in.
            round.setActingSeat(nextSeatInToAct(round, round.actingSeat()));
            return;
        }
        advanceToNextStreetOrShowdown(round);
    }

    private void applyChips(GameRound round, Player player, long amount) {
        player.removeFromStack(amount);
        round.pot().contribute(player.id(), amount);
        long already = round.streetContributions().getOrDefault(player.id(), 0L);
        round.streetContributions().put(player.id(), already + amount);
    }

    private void reopenActionExcept(GameRound round, UUID exceptPlayerId) {
        round.toAct().clear();
        for (int idx : seatsThatCanAct(round)) {
            UUID id = seats[idx].player().id();
            if (!id.equals(exceptPlayerId)) {
                round.toAct().add(id);
            }
        }
    }

    private int nextSeatInToAct(GameRound round, int from) {
        int n = seats.length;
        for (int step = 1; step <= n; step++) {
            int idx = (from + step) % n;
            Player p = seats[idx].player();
            if (p != null && round.toAct().contains(p.id())) {
                return idx;
            }
        }
        throw new IllegalStateException("no seat left to act");
    }

    // === Showdown ===

    private void awardUncontestedPot(GameRound round) {
        UUID winner = seats[firstSeatInHand(round)].player().id();
        findPlayer(winner).addToStack(round.pot().total());
        round.setStage(RoundStage.COMPLETE);
        finishHand();
    }

    private void resolveShowdown(GameRound round) {
        round.setStage(RoundStage.SHOWDOWN);
        Set<UUID> folded = new HashSet<>();
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && p.status() == PlayerStatus.FOLDED) {
                folded.add(p.id());
            }
        }

        for (Pot.SidePot sidePot : round.pot().resolve(folded)) {
            List<UUID> winners = bestHandWinners(round, sidePot.eligiblePlayers());
            splitPotAmongWinners(sidePot.amount(), winners);
        }

        round.setStage(RoundStage.COMPLETE);
        finishHand();
    }

    private List<UUID> bestHandWinners(GameRound round, Set<UUID> eligiblePlayers) {
        Map<UUID, EvaluatedHand> evaluated = new HashMap<>();
        EvaluatedHand best = null;
        for (UUID id : eligiblePlayers) {
            List<Card> sevenCards = new ArrayList<>(round.holeCards().get(id).cards());
            sevenCards.addAll(round.board());
            EvaluatedHand hand = HandEvaluator.evaluate(sevenCards);
            evaluated.put(id, hand);
            if (best == null || hand.compareTo(best) > 0) {
                best = hand;
            }
        }
        EvaluatedHand finalBest = best;
        return evaluated.entrySet().stream()
                .filter(e -> e.getValue().equals(finalBest))
                .map(Map.Entry::getKey)
                .toList();
    }

    private void splitPotAmongWinners(long amount, List<UUID> winners) {
        if (winners.size() == 1) {
            findPlayer(winners.get(0)).addToStack(amount);
            return;
        }
        long share = amount / winners.size();
        long remainder = amount % winners.size();
        List<UUID> ordered = orderBySeatFromButton(winners);
        for (int i = 0; i < ordered.size(); i++) {
            long extra = (i < remainder) ? 1 : 0;
            findPlayer(ordered.get(i)).addToStack(share + extra);
        }
    }

    // Odd chip goes to the first tied player left of the button - order winners by seat
    // distance clockwise from the seat just after the button.
    private List<UUID> orderBySeatFromButton(List<UUID> playerIds) {
        Map<UUID, Integer> seatIndexById = new HashMap<>();
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null) seatIndexById.put(p.id(), seat.index());
        }
        int n = seats.length;
        List<UUID> ordered = new ArrayList<>(playerIds);
        ordered.sort(Comparator.comparingInt(id -> (seatIndexById.get(id) - dealerSeat - 1 + n) % n));
        return ordered;
    }

    private void finishHand() {
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && p.stack() == 0 && p.status() != PlayerStatus.SITTING_OUT) {
                p.setStatus(PlayerStatus.SITTING_OUT);
            }
        }
        for (UUID id : pendingLeaves) {
            removeFromSeat(id);
        }
        pendingLeaves.clear();

        if (pendingClose) {
            closed = true;
            currentRound = null;
            return;
        }

        if (occupiedSeatCount() >= 2 && !closed) {
            startHand();
        } else {
            currentRound = null;
        }
    }

    // === Seat helpers ===

    private int firstOccupiedSeatIndex() {
        for (Seat seat : seats) {
            if (!seat.isEmpty()) return seat.index();
        }
        throw new IllegalStateException("no seated players");
    }

    private int firstSeatInHand(GameRound round) {
        for (int idx : seatsInHand(round)) {
            return idx;
        }
        throw new IllegalStateException("no players left in hand");
    }

    // Who's eligible to be dealt into a brand-new hand: seated, not sitting out. Table-wide
    // live scan on purpose - there's no round yet at the point this is called from startHand().
    private List<Integer> seatsEligibleToBeDealt() {
        List<Integer> result = new ArrayList<>();
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && p.status() != PlayerStatus.SITTING_OUT) {
                result.add(seat.index());
            }
        }
        return result;
    }

    // ACTIVE or ALL_IN AND actually dealt into this round - still in the hand, not folded.
    // Scoped to round.holeCards() so a player who sits down mid-hand (defaults to ACTIVE)
    // can never be mistaken for someone still live in a hand they were never dealt into.
    private List<Integer> seatsInHand(GameRound round) {
        List<Integer> result = new ArrayList<>();
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && round.holeCards().containsKey(p.id())
                    && (p.status() == PlayerStatus.ACTIVE || p.status() == PlayerStatus.ALL_IN)) {
                result.add(seat.index());
            }
        }
        return result;
    }

    // Strictly ACTIVE and dealt into this round - can still voluntarily act.
    private List<Integer> seatsThatCanAct(GameRound round) {
        List<Integer> result = new ArrayList<>();
        for (Seat seat : seats) {
            Player p = seat.player();
            if (p != null && round.holeCards().containsKey(p.id()) && p.status() == PlayerStatus.ACTIVE) {
                result.add(seat.index());
            }
        }
        return result;
    }
}
