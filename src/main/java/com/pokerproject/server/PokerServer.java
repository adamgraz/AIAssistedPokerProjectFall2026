package com.pokerproject.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokerproject.domain.GameConfig;
import com.pokerproject.domain.Table;
import com.pokerproject.protocol.ActionType;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Phase 2: pure plumbing. Every incoming message maps 1:1 onto a Table.apply(...) call
// already proven correct in Phase 1 - no betting/hand-lifecycle logic lives here. The only
// real logic added at this layer is per-viewer hole-card redaction (SnapshotBuilder), which
// is a wire-serialization concern, not a game rule.
public final class PokerServer {

    private static final int DEFAULT_PORT = 7070;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Table table;
    private final Map<WsContext, UUID> connections = new ConcurrentHashMap<>();

    public PokerServer(Table table) {
        this.table = table;
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

    private void onConnect(WsConnectContext ctx) {
        UUID playerId = UUID.randomUUID();
        connections.put(ctx, playerId);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("playerId", playerId.toString());
        ctx.send(new Envelope("WELCOME", payload));
    }

    private void onClose(WsCloseContext ctx) {
        // Deferred to Phase 4: a closed socket does NOT remove the player from the table -
        // reconnect handling isn't built yet, so leaving them seated is the safer default.
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

        synchronized (table) {
            try {
                dispatch(playerId, envelope);
            } catch (IllegalStateException e) {
                sendError(ctx, e.getMessage());
                return;
            }
            broadcastState();
        }
    }

    private void dispatch(UUID playerId, Envelope envelope) {
        String type = envelope.type();
        JsonNode payload = envelope.payload() != null ? envelope.payload() : MAPPER.createObjectNode();

        if (isTableCommand(type)) {
            String displayName = payload.path("displayName").asText(null);
            long amount = payload.path("amount").asLong(0);
            table.apply(new TableCommandRequest(playerId, TableCommand.valueOf(type), displayName, amount));
        } else if (isActionType(type)) {
            long amount = payload.path("amount").asLong(0);
            table.apply(new PlayerAction(playerId, ActionType.valueOf(type), amount));
        } else {
            throw new IllegalStateException("unknown message type: " + type);
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
        for (Map.Entry<WsContext, UUID> entry : connections.entrySet()) {
            TableSnapshot snapshot = SnapshotBuilder.build(table, entry.getValue());
            entry.getKey().send(new Envelope("STATE", MAPPER.valueToTree(snapshot)));
        }
    }

    private void sendError(WsContext ctx, String message) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("message", message);
        ctx.send(new Envelope("ERROR", payload));
    }

    public static void main(String[] args) {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        new PokerServer(table).start();
    }
}
