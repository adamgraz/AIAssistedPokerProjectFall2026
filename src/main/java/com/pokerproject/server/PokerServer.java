package com.pokerproject.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokerproject.db.DB;
import com.pokerproject.db.PasswordHasher;
import com.pokerproject.db.Profile;
import com.pokerproject.db.TableState;
import com.pokerproject.domain.GameConfig;
import com.pokerproject.domain.Player;
import com.pokerproject.domain.Seat;
import com.pokerproject.domain.Table;
import com.pokerproject.protocol.ActionType;
import com.pokerproject.protocol.AuthCommand;
import com.pokerproject.protocol.Envelope;
import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.SnapshotBuilder;
import com.pokerproject.protocol.TableCommand;
import com.pokerproject.protocol.TableCommandRequest;
import com.pokerproject.protocol.TableSnapshot;
import io.javalin.Javalin;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Pure plumbing. Every incoming message maps 1:1 onto a Table.apply(...) call already
// proven correct in Table's own tests - no betting/hand-lifecycle logic lives here. The only
// real logic added at this layer is per-viewer hole-card redaction (SnapshotBuilder), which
// is a wire-serialization concern, not a game rule. The one exception is the bomb pot opt-in
// timeout below - Table has no notion of real time or a clock, so "60 seconds" has to live
// at the layer that does.
public final class PokerServer {

    private static final int DEFAULT_PORT = 7070;
    // Not sent over the wire - App.jsx's countdown is cosmetic only and keeps its own copy
    // (BOMB_POT_OPT_IN_SECONDS) in sync by hand. This value is what actually closes the window.
    private static final long BOMB_POT_OPT_IN_TIMEOUT_SECONDS = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Table table;
    private final Map<WsContext, UUID> connections = new ConcurrentHashMap<>();
    // Daemon thread: PokerServer has no explicit shutdown() today (tests just create a new
    // instance per test), so a non-daemon scheduler thread would leak and keep the JVM alive.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bomb-pot-opt-in-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> bombPotOptInTimer;

    public PokerServer(Table table) {
        this.table = table;
        table.setOnHandComplete(() -> {
            recordHandsPlayed();
            snapshotAllSeated();
            broadcastState();
        });
        table.setOnPlayerLeftSeat(this::recordNetChipsOnLeave);
        table.setOnRebuy((playerId, player) -> snapshotSeat(player));
        table.setOnTableClosed(TableState::clearAll);
        table.setOnBombPotOptInWindowOpened(this::scheduleBombPotOptInTimeout);
        table.setOnBombPotOptInWindowResolved(this::cancelBombPotOptInTimer);
    }

    // Every player dealt into the hand that just completed - folded or not, showdown or not,
    // since onHandComplete fires identically either way. A guest's empty Optional just means
    // the lambda never runs, no branch to remember at the call site.
    private void recordHandsPlayed() {
        var round = table.currentRound();
        if (round == null) {
            return;
        }
        for (UUID playerId : round.holeCards().keySet()) {
            Profile.findByUuid(playerId).ifPresent(Profile::recordHandPlayed);
        }
    }

    // table_state's crash-recovery snapshot, refreshed after every hand and every rebuy -
    // both guest and profile-backed players get one, since a guest's stack is exactly as much
    // at risk from a crash as a logged-in player's (see TableState).
    private void snapshotAllSeated() {
        for (Seat seat : table.seats()) {
            Player player = seat.player();
            if (player != null) {
                snapshotSeat(player);
            }
        }
    }

    private void snapshotSeat(Player player) {
        TableState.snapshot(player.id(), player.displayName(), player.stack(), player.totalBuyIn());
    }

    // Fires once per profile, at the moment their seat is actually vacated - not per hand, so
    // mid-session rebuys never get double-counted as "winnings". The table_state row is
    // deleted here too - once a result is permanently recorded, the scratch copy has to go, or
    // a re-seated guest UUID could inherit someone else's stale row.
    private void recordNetChipsOnLeave(UUID playerId, Player player) {
        Profile.findByUuid(playerId).ifPresent(profile ->
                profile.recordNetChips(player.stack() - player.totalBuyIn()));
        TableState.deleteFor(playerId);
    }

    // Scheduled once per opt-in window, the moment it opens. If the window resolves early
    // (everyone responds before the timeout), onBombPotOptInWindowResolved cancels this -
    // Table.expireBombPotOptInWindow() is also a safe no-op on a stale/already-closed window,
    // so a race between the two is harmless either way.
    private void scheduleBombPotOptInTimeout() {
        bombPotOptInTimer = scheduler.schedule(() -> {
            synchronized (table) {
                table.expireBombPotOptInWindow();
            }
            broadcastState();
        }, BOMB_POT_OPT_IN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelBombPotOptInTimer() {
        if (bombPotOptInTimer != null) {
            bombPotOptInTimer.cancel(false);
        }
    }

    public Javalin start() {
        return start(DEFAULT_PORT);
    }

    public Javalin start(int port) {
        Javalin app = Javalin.create(config -> config.routes.ws("/ws", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
        }));
        return app.start(port);
    }

    // Guest by default, unchanged from before login existed: a fresh random UUID, zero
    // friction, no backing Profile row. LOGIN/CREATE_PROFILE (below) aren't a gate in front of
    // this - they're optional messages a client can send later to upgrade this connection's
    // identity to a profile-backed one.
    private void onConnect(WsConnectContext ctx) {
        UUID playerId = UUID.randomUUID();
        connections.put(ctx, playerId);
        sendWelcome(ctx, playerId, null);
    }

    private void sendWelcome(WsContext ctx, UUID playerId, Profile profile) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("playerId", playerId.toString());
        ArrayNode modes = payload.putArray("availableModes");
        for (GameVariant variant : GameVariant.values()) {
            modes.add(variant.name());
        }
        if (profile != null) {
            payload.put("displayName", profile.getDisplayName());
            payload.put("handsPlayed", profile.getHandsPlayed());
            payload.put("netChips", profile.getNetChips());
        }
        ctx.send(new Envelope("WELCOME", payload));
    }

    private void onClose(WsCloseContext ctx) {
        // A closed socket does NOT remove the player from the table - reclaiming your own
        // seat on reconnect isn't built yet (see the Notes vault's "Next up" list), so leaving
        // them seated is the safer default. The auto-reconnecting client (useGameSocket.js)
        // re-opens a fresh connection, but arrives as a new, unseated visitor, not the same
        // seat - REMOVE_PLAYER is the only way to free an orphaned seat today.
        connections.remove(ctx);
    }

    private void onMessage(WsMessageContext ctx) {
        UUID playerId = connections.get(ctx);
        Envelope envelope;
        try {
            envelope = MAPPER.readValue(ctx.message(), Envelope.class);
        } catch (Exception e) {
            sendError(ctx, "malformed message");
            return;
        }

        if ("PING".equals(envelope.type())) {
            // Heartbeat only, no reply needed - Jetty has its own idle-timeout on a quiet
            // WebSocket, and any traffic (even inbound) resets that clock. Without this, a
            // long stretch of no real messages (thinking, between hands) eventually gets the
            // connection dropped server-side even though nothing's actually wrong.
            return;
        }

        synchronized (table) {
            try {
                dispatch(ctx, playerId, envelope);
            } catch (IllegalStateException e) {
                sendError(ctx, e.getMessage());
                return;
            }
            broadcastState();
        }
    }

    private void dispatch(WsContext ctx, UUID playerId, Envelope envelope) {
        String type = envelope.type();
        JsonNode payload = envelope.payload() != null ? envelope.payload() : MAPPER.createObjectNode();

        if (isAuthCommand(type)) {
            handleAuthCommand(ctx, AuthCommand.valueOf(type), payload);
        } else if (isTableCommand(type)) {
            String displayName = payload.path("displayName").asText(null);
            long amount = payload.path("amount").asLong(0);
            String gameVariant = payload.path("mode").asText(null);
            String targetPlayerId = payload.path("targetPlayerId").asText(null);
            TableCommand command = TableCommand.valueOf(type);
            if (command == TableCommand.REMOVE_PLAYER) {
                verifyTargetIsOrphaned(targetPlayerId);
            }
            table.apply(new TableCommandRequest(playerId, command, displayName, amount, gameVariant, targetPlayerId));
        } else if (isActionType(type)) {
            long amount = payload.path("amount").asLong(0);
            table.apply(new PlayerAction(playerId, ActionType.valueOf(type), amount));
        } else {
            throw new IllegalStateException("unknown message type: " + type);
        }
    }

    // LOGIN/CREATE_PROFILE never reach Table.apply(...) - Table has no notion of profiles or
    // passphrases. Both run inside the same synchronized(table) block as everything else,
    // which is what closes two races that would otherwise exist: two people claiming the same
    // brand-new passphrase at once (a database UNIQUE constraint can't catch this - PBKDF2
    // salts every hash, so the same passphrase hashed twice never produces the same stored
    // value), and the same profile logging in from two connections at once.
    private void handleAuthCommand(WsContext ctx, AuthCommand command, JsonNode payload) {
        String passphrase = payload.path("passphrase").asText(null);
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException("passphrase is required");
        }
        Profile profile = switch (command) {
            case LOGIN -> {
                Profile found = Profile.findByPassphrase(passphrase);
                if (found == null) {
                    throw new IllegalStateException("unknown passphrase");
                }
                yield found;
            }
            case CREATE_PROFILE -> {
                String displayName = payload.path("displayName").asText(null);
                if (displayName == null || displayName.isBlank()) {
                    throw new IllegalStateException("display name is required");
                }
                if (Profile.findByPassphrase(passphrase) != null) {
                    throw new IllegalStateException("that passphrase is already taken");
                }
                Profile created = new Profile(displayName, PasswordHasher.hash(passphrase));
                created.create();
                yield created;
            }
        };

        // verifyTargetIsOrphaned's check, reused in reverse: reject a login for an identity
        // that's already live on another connection instead of letting a second one claim it.
        if (connections.containsValue(profile.getPlayerUuid())) {
            throw new IllegalStateException("that profile is already logged in elsewhere");
        }
        connections.put(ctx, profile.getPlayerUuid());
        sendWelcome(ctx, profile.getPlayerUuid(), profile);
    }

    // The one piece of business logic at this layer: only PokerServer knows which playerIds
    // have a live connection right now (connections' values), Table has no notion of a
    // connection at all - so this has to be checked here, before the command ever reaches it.
    private void verifyTargetIsOrphaned(String targetPlayerId) {
        UUID target;
        try {
            target = UUID.fromString(targetPlayerId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("invalid target player id");
        }
        if (connections.containsValue(target)) {
            throw new IllegalStateException("that player is still connected - can't remove them");
        }
    }

    private boolean isAuthCommand(String type) {
        try {
            AuthCommand.valueOf(type);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean isTableCommand(String type) {
        try {
            TableCommand.valueOf(type);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean isActionType(String type) {
        try {
            ActionType.valueOf(type);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private void broadcastState() {
        Set<UUID> connectedIds = new HashSet<>(connections.values());
        for (Map.Entry<WsContext, UUID> entry : connections.entrySet()) {
            TableSnapshot snapshot = SnapshotBuilder.build(table, entry.getValue(), connectedIds);
            entry.getKey().send(new Envelope("STATE", MAPPER.valueToTree(snapshot)));
        }
    }

    private void sendError(WsContext ctx, String message) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("message", message);
        ctx.send(new Envelope("ERROR", payload));
    }

    // Restores whatever table_state still remembers before the first connection is accepted.
    // Reuses the normal SIT_DOWN path (same validation, same seat-picking, same Player
    // construction the wire protocol already uses) instead of a parallel seating mechanism -
    // then patches the stack up/down from the buy-in SIT_DOWN just set, since rebuy is the
    // only existing way to grow totalBuyIn and stack together. Doesn't attempt to restore seat
    // ordering, dealer position, or an in-progress hand - only "nobody's chip count is ever
    // permanently lost" is in scope (see persistence-design.html).
    // Package-private, not private: PokerServerIntegrationTest calls this directly against a
    // freshly constructed Table, so the crash-recovery path is provable without an actual
    // process restart.
    static void rehydrateTableState(Table table) {
        TableState.pruneStale();
        for (TableState.Row row : TableState.all()) {
            table.apply(new TableCommandRequest(
                    row.playerUuid(), TableCommand.SIT_DOWN, row.displayName(), row.totalBuyIn(), null, null));
            Player player = table.roster().stream()
                    .filter(p -> p.id().equals(row.playerUuid()))
                    .findFirst()
                    .orElseThrow();
            long adjustment = row.stack() - row.totalBuyIn();
            if (adjustment != 0) {
                player.addToStack(adjustment);
            }
        }
    }

    public static void main(String[] args) {
        DB.initSchema();
        // 9 seats, 5/10 blinds, 10-chip bomb pot ante - change a value, restart the instance
        // (see GameConfig's own doc comment; no live reload, no CLI/env config by design).
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 10));
        rehydrateTableState(table);
        new PokerServer(table).start();
    }
}
