package com.pokerproject.domain;

import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.TableCommandRequest;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class Table {

    private final Seat[] seats;
    private final List<Player> roster = new ArrayList<>();
    private int dealerSeat = -1; // -1 means no hand has been played yet
    private GameVariant variant;
    private final GameConfig config;
    private GameRound currentRound;
    private boolean closed;

    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> pendingLeaves = new HashSet<>();
    private boolean pendingClose;
    private Runnable onHandComplete;
    // Fires from inside removeFromSeat with the final Player state (stack, totalBuyIn) still
    // intact - the one place every leave/kick path (LEAVE_TABLE, REMOVE_PLAYER, finishHand's
    // pending-leaves cleanup) already funnels through, so persistence only needs one hook here
    // rather than one per caller.
    private BiConsumer<UUID, Player> onPlayerLeftSeat;
    // Fires at the end of handleRebuy - a rebuy changes a seated player's stack on its own
    // timing, not gated by the next hand finishing, so persistence snapshots it immediately
    // instead of waiting for onHandComplete.
    private BiConsumer<UUID, Player> onRebuy;
    // Fires the moment `closed` actually becomes true - either immediately (handleEndTable,
    // between hands) or deferred (finishHand, once a pending close resolves). Both paths route
    // through markClosed() below so this only needs wiring in one place.
    private Runnable onTableClosed;

    // Votes for which GameVariant the NEXT hand should use. Only accepted while votingOpen -
    // a player opens the window with NEXT_HAND once the previous hand has finished; the moment
    // a winner is decided, the hand deals immediately and this resets for the window after that.
    private final Map<UUID, GameVariant> modeVotes = new HashMap<>();
    private boolean votingOpen;

    // Bomb pot opt-in: opened instead of dealing, the moment a bomb pot variant is decided.
    // Only accepted while bombPotOptInOpen. Closes (and deals, if enough opted in) once every
    // eligible player has responded or the wire layer's 60s timeout defaults the stragglers.
    private final Map<UUID, Boolean> bombPotOptIns = new HashMap<>();
    private boolean bombPotOptInOpen;
    private GameVariant pendingBombPotVariant;
    private Runnable onBombPotOptInWindowOpened;
    private Runnable onBombPotOptInWindowResolved;

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

    // Live vote counts for the next hand's mode, keyed by mode.
    public Map<GameVariant, Long> voteTally() {
        Map<GameVariant, Long> tally = new HashMap<>();
        for (GameVariant v : modeVotes.values()) {
            tally.merge(v, 1L, Long::sum);
        }
        return tally;
    }

    public GameVariant voteOf(UUID playerId) {
        return modeVotes.get(playerId);
    }

    // Raw playerId -> vote for the in-progress voting window. Not secret info - the wire
    // layer uses this to group voters by mode and list who hasn't voted, same for every viewer.
    public Map<UUID, GameVariant> votes() {
        return Map.copyOf(modeVotes);
    }

    // True between a NEXT_HAND request and the moment a mode is decided and the hand deals.
    public boolean votingOpen() {
        return votingOpen;
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

    // Fires right after a hand reaches COMPLETE (revealed hole cards, final pot) but before
    // finishHand() chains into the next hand or nulls the round - the only point where that
    // stage is observable at all. Wire layer uses this to broadcast the showdown snapshot
    // that would otherwise never go out, since finishHand() runs synchronously inside apply().
    public void setOnRebuy(BiConsumer<UUID, Player> listener) {
        this.onRebuy = listener;
    }

    public void setOnTableClosed(Runnable listener) {
        this.onTableClosed = listener;
    }

    public void setOnHandComplete(Runnable listener) {
        this.onHandComplete = listener;
    }

    public void setOnPlayerLeftSeat(BiConsumer<UUID, Player> listener) {
        this.onPlayerLeftSeat = listener;
    }

    // Fires the moment a bomb pot variant is decided and the opt-in window opens - the wire
    // layer's cue to schedule its 60s auto-opt-out timeout for this window.
    public void setOnBombPotOptInWindowOpened(Runnable listener) {
        this.onBombPotOptInWindowOpened = listener;
    }

    // Fires once the opt-in window closes for any reason (everyone responded, or the
    // timeout defaulted the rest) - the wire layer's cue to cancel a still-pending timer,
    // since the window it was scheduled for no longer exists.
    public void setOnBombPotOptInWindowResolved(Runnable listener) {
        this.onBombPotOptInWindowResolved = listener;
    }

    public boolean bombPotOptInOpen() {
        return bombPotOptInOpen;
    }

    public GameVariant pendingBombPotVariant() {
        return pendingBombPotVariant;
    }

    // Raw playerId -> opted-in for the in-progress window. Not secret info, same as votes().
    public Map<UUID, Boolean> bombPotOptIns() {
        return Map.copyOf(bombPotOptIns);
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
            case VOTE_GAME_MODE -> handleVoteGameMode(request);
            case NEXT_HAND -> handleNextHand(request);
            case REMOVE_PLAYER -> handleRemovePlayer(request);
            case BOMB_POT_OPT -> handleBombPotOpt(request);
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
        // No hand ever auto-starts, including the first - always waits for a player to call
        // NEXT_HAND (see handleNextHand/checkVoteOutcome), so everyone gets a chance to join
        // before the night's first hand deals.
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
        if (onRebuy != null) {
            onRebuy.accept(player.id(), player);
        }
    }

    private void handleEndTable(TableCommandRequest request) {
        if (isBetweenHands()) {
            markClosed();
        } else {
            pendingClose = true;
        }
    }

    private void markClosed() {
        closed = true;
        currentRound = null;
        if (onTableClosed != null) {
            onTableClosed.run();
        }
    }

    private void handleNextHand(TableCommandRequest request) {
        findPlayer(request.playerId()); // must be seated; throws otherwise
        if (!isBetweenHands()) {
            throw new IllegalStateException("hand still in progress");
        }
        if (bombPotOptInOpen) {
            throw new IllegalStateException("bomb pot opt-in is still open");
        }
        if (occupiedSeatCount() < 2) {
            throw new IllegalStateException("need at least 2 players for another hand");
        }
        votingOpen = true; // idempotent - a second click while already open is a harmless no-op
    }

    // Removes an orphaned seat - PokerServer has already verified the target has no live
    // connection before this is ever dispatched (Table has no notion of connections, so it
    // can't check that itself). Unlike LEAVE_TABLE this is never deferred: a seat whose
    // connection is gone forever can't be waited on to finish a hand it can never act in.
    private void handleRemovePlayer(TableCommandRequest request) {
        findPlayer(request.playerId()); // the requester must themselves be seated
        UUID target;
        try {
            target = UUID.fromString(request.targetPlayerId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("invalid target player id");
        }
        Player targetPlayer = findPlayer(target); // throws if already gone

        // !isBetweenHands() rules out COMPLETE too, not just a live round - forceFold ends in
        // awardUncontestedPot for a heads-up removal, and re-running that against an already-
        // COMPLETE round (still holding ACTIVE status right up until finishHand's cleanup)
        // would double-pay whoever's left. Only a genuinely live betting street forces a fold.
        if (!isBetweenHands() && currentRound.holeCards().containsKey(target)
                && (targetPlayer.status() == PlayerStatus.ACTIVE || targetPlayer.status() == PlayerStatus.ALL_IN)) {
            forceFold(currentRound, targetPlayer);
        }
        removeFromSeat(target);
        pendingLeaves.remove(target);
    }

    // Same bookkeeping as a real FOLD, but only advances actingSeat if the removed player was
    // actually the one on the clock - otherwise whoever's legitimately still up stays up.
    private void forceFold(GameRound round, Player player) {
        boolean wasActing = round.actingSeat() >= 0
                && seats[round.actingSeat()].player() != null
                && seats[round.actingSeat()].player().id().equals(player.id());

        player.setStatus(PlayerStatus.FOLDED);
        round.toAct().remove(player.id());

        if (seatsInHand(round).size() <= 1) {
            awardUncontestedPot(round);
            return;
        }
        if (!wasActing) {
            return;
        }
        if (!round.toAct().isEmpty()) {
            round.setActingSeat(nextSeatInToAct(round, round.actingSeat()));
            return;
        }
        advanceToNextStreetOrShowdown(round);
    }

    private void handleBombPotOpt(TableCommandRequest request) {
        Player player = findPlayer(request.playerId()); // must be seated; throws otherwise
        if (!bombPotOptInOpen) {
            throw new IllegalStateException("bomb pot opt-in isn't open");
        }
        bombPotOptIns.put(player.id(), request.amount() != 0);
        maybeCloseBombPotOptInWindow();
    }

    private void maybeCloseBombPotOptInWindow() {
        List<Integer> eligible = seatsEligibleToBeDealt();
        long responded = eligible.stream()
                .filter(idx -> bombPotOptIns.containsKey(seats[idx].player().id()))
                .count();
        if (responded < eligible.size()) {
            return; // still waiting on someone
        }
        closeBombPotOptInWindow();
    }

    // Called by the wire layer 60s after the window opened - anyone who hasn't responded yet
    // is defaulted to opted-out. No-op if the window already resolved naturally (everyone
    // responded early) before the timer fired.
    public void expireBombPotOptInWindow() {
        if (!bombPotOptInOpen) {
            return;
        }
        for (int idx : seatsEligibleToBeDealt()) {
            UUID id = seats[idx].player().id();
            bombPotOptIns.putIfAbsent(id, false);
        }
        closeBombPotOptInWindow();
    }

    private void closeBombPotOptInWindow() {
        bombPotOptInOpen = false;
        List<UUID> optedIn = new ArrayList<>();
        for (int idx : seatsEligibleToBeDealt()) {
            UUID id = seats[idx].player().id();
            if (bombPotOptIns.getOrDefault(id, false)) {
                optedIn.add(id);
            }
        }
        bombPotOptIns.clear();

        GameVariant decided = pendingBombPotVariant;
        pendingBombPotVariant = null;
        if (optedIn.size() < 2) {
            // Not enough takers to actually play a bomb pot - back to voting instead of
            // dealing a hand nobody can contest, no need to make someone click NEXT_HAND again.
            votingOpen = true;
        } else {
            startBombPotHand(decided, optedIn);
        }
        if (onBombPotOptInWindowResolved != null) {
            onBombPotOptInWindowResolved.run();
        }
    }

    private void handleVoteGameMode(TableCommandRequest request) {
        findPlayer(request.playerId()); // must be seated to vote; throws otherwise
        if (!votingOpen) {
            throw new IllegalStateException("voting isn't open - call NEXT_HAND first");
        }
        GameVariant vote;
        try {
            vote = GameVariant.valueOf(request.gameVariant());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("unknown game mode: " + request.gameVariant());
        }
        modeVotes.put(request.playerId(), vote);
        checkVoteOutcome();
    }

    // Decides the next hand's mode and deals it in immediately, the moment either becomes
    // true: (a) a mode has mathematically clinched - no other mode could catch up even if
    // every player who hasn't voted yet piled onto whichever single rival is best-positioned
    // to benefit - or (b) every seated player has voted and it's still tied, so there's no
    // reason to keep waiting; the tie is broken at random.
    // ponytail: doesn't re-check when a non-voting player leaves mid-vote (would shrink
    // "remaining" and might newly decide it) - only re-checked on the next actual vote. Add
    // if a stalled vote near the end of a hand turns out to matter in practice.
    private void checkVoteOutcome() {
        Map<GameVariant, Long> tally = voteTally();
        long maxCount = tally.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (maxCount == 0) {
            return;
        }
        List<GameVariant> leaders = tally.entrySet().stream()
                .filter(e -> e.getValue() == maxCount)
                .map(Map.Entry::getKey)
                .toList();
        long remaining = Math.max(0, occupiedSeatCount() - modeVotes.size());

        GameVariant decided = null;
        if (leaders.size() == 1) {
            GameVariant leader = leaders.get(0);
            List<GameVariant> rivals = Arrays.stream(GameVariant.values())
                    .filter(v -> v != leader)
                    .toList();
            long bestRivalCurrent = rivals.stream().mapToLong(v -> tally.getOrDefault(v, 0L)).max().orElse(0);
            if (rivals.isEmpty() || maxCount > bestRivalCurrent + remaining) {
                decided = leader;
            }
        }
        if (decided == null && remaining == 0) {
            decided = leaders.get(random.nextInt(leaders.size())); // deadlocked, everyone's voted
        }
        if (decided != null) {
            if (isBombPot(decided)) {
                openBombPotOptInWindow(decided);
            } else {
                startHand(decided);
            }
        }
    }

    private void openBombPotOptInWindow(GameVariant decided) {
        pendingBombPotVariant = decided;
        modeVotes.clear();
        votingOpen = false;
        bombPotOptIns.clear();
        bombPotOptInOpen = true;
        if (onBombPotOptInWindowOpened != null) {
            onBombPotOptInWindowOpened.run();
        }
    }

    private static boolean isBombPot(GameVariant variant) {
        return switch (variant) {
            case TEXAS_HOLDEM, OMAHA -> false;
            case TEXAS_BOMB_POT, OMAHA_BOMB_POT -> true;
        };
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
                if (onPlayerLeftSeat != null) {
                    onPlayerLeftSeat.accept(playerId, p);
                }
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

    private void startHand(GameVariant nextVariant) {
        variant = nextVariant;
        modeVotes.clear();
        votingOpen = false;

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

        int holeCardCount = formationRuleFor(variant).holeCardCount();
        for (int idx : inHand) {
            Player p = seats[idx].player();
            List<Card> cards = new ArrayList<>();
            for (int i = 0; i < holeCardCount; i++) {
                cards.add(round.deck().draw());
            }
            round.holeCards().put(p.id(), new HoleCards(cards));
        }

        // Guards the same way as dealNextStreet: if both blinds happened to bust a player
        // straight to all-in, nobody's left to act and this value is never actually used.
        int firstToAct = seatsThatCanAct(round).isEmpty() ? bigBlindSeat : nextActiveSeat(bigBlindSeat);
        beginStreet(round, RoundStage.PREFLOP, firstToAct, config.bigBlind(), config.bigBlind());
    }

    // A bomb pot: only the opted-in players are dealt in, everyone posts a fixed ante instead
    // of blinds, no preflop betting - both boards' flops go down immediately and the first
    // live betting round starts straight at FLOP.
    private void startBombPotHand(GameVariant nextVariant, List<UUID> optedIn) {
        variant = nextVariant;
        modeVotes.clear();
        votingOpen = false;

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
        round.boards().add(new ArrayList<>()); // the double-board format's second board
        round.setSmallBlindSeat(-1); // no blinds this hand - ante only
        round.setBigBlindSeat(-1);

        for (UUID id : optedIn) {
            postChipsFromStack(round, findPlayer(id), config.bombPotAnte());
        }
        // Unlike a blind, the ante isn't "owed" going into the first betting round - everyone
        // posted the same amount, so nobody should be facing a bet they still need to call.
        round.streetContributions().clear();

        int holeCardCount = formationRuleFor(variant).holeCardCount();
        for (UUID id : optedIn) {
            List<Card> cards = new ArrayList<>();
            for (int i = 0; i < holeCardCount; i++) {
                cards.add(round.deck().draw());
            }
            round.holeCards().put(id, new HoleCards(cards));
        }

        for (List<Card> board : round.boards()) {
            for (int i = 0; i < 3; i++) {
                board.add(round.deck().draw());
            }
        }

        int firstToAct = seatsThatCanAct(round).isEmpty() ? dealerSeat : nextSeatInHand(round, dealerSeat);
        beginStreet(round, RoundStage.FLOP, firstToAct, 0, config.bigBlind());
    }

    private void postBlind(GameRound round, int seatIndex, long amount) {
        postChipsFromStack(round, seats[seatIndex].player(), amount);
    }

    // Shared by blinds (normal hands) and the ante (bomb pot hands) - both just move up to
    // `amount` from a player's stack into the pot, capped at whatever they actually have.
    private void postChipsFromStack(GameRound round, Player player, long amount) {
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
        round.lastActionByPlayer().clear(); // new street - nobody's acted on it yet
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
        for (List<Card> board : round.boards()) {
            for (int i = 0; i < cardsToDeal; i++) {
                board.add(round.deck().draw());
            }
        }
        round.streetContributions().clear();
        // If nobody can act (everyone still in the hand is already all-in), there's no
        // "first to act" - beginStreet detects that and runs the rest out; this value is
        // never actually used in that case, so a harmless placeholder is fine.
        int firstToAct = seatsThatCanAct(round).isEmpty() ? dealerSeat : firstToActForNextStreet(round);
        beginStreet(round, next, firstToAct, 0, config.bigBlind());
    }

    // Normal hands: first active seat left of the button acts first postflop (unchanged).
    // Bomb pots: same rule for every betting round, since there's no blind-based order to
    // fall back on - the button is the only positional anchor a bomb pot hand has.
    private int firstToActForNextStreet(GameRound round) {
        return isBombPot(variant) ? nextSeatInHand(round, dealerSeat) : nextActiveSeat(dealerSeat);
    }

    // First seat clockwise from `from` that's both still in the hand (ACTIVE/ALL_IN) and
    // actually dealt into this round - unlike nextActiveSeat, excludes anyone not holding
    // cards this hand (a bomb pot's opted-out players keep their normal ACTIVE status but
    // were never dealt in, same as seatsInHand/seatsThatCanAct already exclude them).
    private int nextSeatInHand(GameRound round, int from) {
        int n = seats.length;
        for (int step = 1; step <= n; step++) {
            int idx = (from + step) % n;
            Player p = seats[idx].player();
            if (p != null && round.holeCards().containsKey(p.id())
                    && (p.status() == PlayerStatus.ACTIVE || p.status() == PlayerStatus.ALL_IN)) {
                return idx;
            }
        }
        throw new IllegalStateException("no seat left in hand");
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
            for (List<Card> board : round.boards()) {
                for (int i = 0; i < cardsToDeal; i++) {
                    board.add(round.deck().draw());
                }
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

        // One common point for every action type - what the UI shows next to a seat is
        // exactly what actually happened, not a per-branch guess.
        round.lastActionByPlayer().put(player.id(), action.type());

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
        // No hands were ever compared (everyone else folded), so there's nothing board-
        // specific about this win - record it against every board that exists so the UI's
        // per-board crown shows up regardless of board count.
        for (int i = 0; i < round.boards().size(); i++) {
            round.winnersByBoard().add(new HashSet<>(Set.of(winner)));
        }
        completeHand(round);
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

        int boardCount = round.boards().size();
        for (int i = 0; i < boardCount; i++) {
            round.bestFiveByBoard().add(new HashMap<>());
            round.winnersByBoard().add(new HashSet<>());
        }

        for (Pot.SidePot sidePot : round.pot().resolve(folded)) {
            long[] shares = splitEvenly(sidePot.amount(), boardCount);
            for (int i = 0; i < boardCount; i++) {
                List<UUID> winners = bestHandWinners(round, sidePot.eligiblePlayers(), i);
                splitPotAmongWinners(shares[i], winners);
                round.winnersByBoard().get(i).addAll(winners);
            }
        }

        completeHand(round);
    }

    // Splits an amount evenly across N boards - a single board just gets the whole amount.
    // Any remainder chip(s) from an odd split go to board 0, same "first board" tiebreak the
    // wire/UI treats as primary.
    private static long[] splitEvenly(long amount, int parts) {
        long[] shares = new long[parts];
        long base = amount / parts;
        long remainder = amount % parts;
        for (int i = 0; i < parts; i++) {
            shares[i] = base + (i < remainder ? 1 : 0);
        }
        return shares;
    }

    private void completeHand(GameRound round) {
        round.setStage(RoundStage.COMPLETE);
        if (onHandComplete != null) {
            onHandComplete.run();
        }
        finishHand();
    }

    private static HandFormationRule formationRuleFor(GameVariant variant) {
        return switch (variant) {
            case TEXAS_HOLDEM, TEXAS_BOMB_POT -> HOLDEM_RULE;
            case OMAHA, OMAHA_BOMB_POT -> OMAHA_RULE;
        };
    }

    private static final HandFormationRule HOLDEM_RULE = new HoldemFormationRule();
    private static final HandFormationRule OMAHA_RULE = new OmahaFormationRule();

    // Evaluated per board - for a double-board bomb pot this runs once per board with the
    // same formation rule and hole cards each time, so e.g. Omaha's "use the same 2 cards on
    // both boards if that's genuinely best" just falls out for free from two independent
    // per-board optimizations, no special-casing needed.
    private List<UUID> bestHandWinners(GameRound round, Set<UUID> eligiblePlayers, int boardIndex) {
        HandFormationRule rule = formationRuleFor(variant);
        List<Card> board = round.boards().get(boardIndex);
        Map<UUID, EvaluatedHand> evaluated = new HashMap<>();
        EvaluatedHand best = null;
        for (UUID id : eligiblePlayers) {
            List<Card> holeCards = round.holeCards().get(id).cards();
            EvaluatedHand hand = rule.evaluate(holeCards, board);
            evaluated.put(id, hand);
            round.bestFiveByBoard().get(boardIndex).put(id, hand.cards());
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
            markClosed();
            return;
        }

        // currentRound is deliberately left in place (still at COMPLETE) - the showdown
        // result stays visible until a player calls NEXT_HAND and a vote decides the next
        // hand's mode; see handleNextHand/checkVoteOutcome.
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
