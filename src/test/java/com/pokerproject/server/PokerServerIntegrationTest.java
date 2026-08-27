package com.pokerproject.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokerproject.domain.GameConfig;
import com.pokerproject.domain.Table;
import com.pokerproject.protocol.GameVariant;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Proves the wire layer itself - message parsing, dispatch onto Table.apply(), per-viewer
// broadcast, and hole-card redaction. Full hand correctness (side pots, showdown, etc.) is
// already proven against Table directly in TableEngineTest; re-proving that over a socket
// would just be slower, not more convincing.
class PokerServerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Javalin app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void twoClientsSitDownAndPlayThroughAFoldOverTheWire() throws Exception {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        app = new PokerServer(table).start(0);
        String url = "ws://localhost:" + app.port() + "/ws";

        TestClient a = new TestClient(url);
        TestClient b = new TestClient(url);

        JsonNode aWelcome = a.next();
        JsonNode bWelcome = b.next();
        assertEquals("WELCOME", aWelcome.path("type").asText());
        assertEquals("WELCOME", bWelcome.path("type").asText());
        String aId = aWelcome.path("payload").path("playerId").asText();
        String bId = bWelcome.path("payload").path("playerId").asText();
        assertNotEquals(aId, bId);

        a.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"A\",\"amount\":1000}}");
        // Both connected clients get this broadcast - B is connected even though not seated.
        JsonNode aOnlySeated = a.next();
        JsonNode bSeesAOnlySeated = b.next();
        assertTrue(aOnlySeated.path("payload").path("round").isNull());
        assertTrue(bSeesAOnlySeated.path("payload").path("round").isNull());

        b.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"B\",\"amount\":1000}}");
        JsonNode aStarted = a.next();
        JsonNode bStarted = b.next();
        assertEquals("PREFLOP", aStarted.path("payload").path("round").path("stage").asText());
        assertEquals("PREFLOP", bStarted.path("payload").path("round").path("stage").asText());

        // Each client only ever sees its own hole cards mid-hand, never the opponent's.
        assertEquals(2, aStarted.path("payload").path("round").path("yourHoleCards").size());
        assertEquals(2, bStarted.path("payload").path("round").path("yourHoleCards").size());
        assertTrue(aStarted.path("payload").path("round").path("revealedHoleCards").isEmpty());
        assertTrue(bStarted.path("payload").path("round").path("revealedHoleCards").isEmpty());

        // Heads-up: whoever sat down first (A) takes seat 0, which is dealer/small blind/
        // first to act on the very first hand - same rule TableEngineTest proves directly.
        a.send("{\"type\":\"FOLD\",\"payload\":{}}");
        JsonNode aNext = a.next();
        JsonNode bNext = b.next();

        // The fold resolves the hand uncontested and immediately chains into a new one -
        // both clients should see a fresh PREFLOP round with blinds already posted.
        assertEquals("PREFLOP", aNext.path("payload").path("round").path("stage").asText());
        assertEquals("PREFLOP", bNext.path("payload").path("round").path("stage").asText());
        assertEquals(15, aNext.path("payload").path("round").path("pot").asLong());
    }

    // Exercises CALL/CHECK/BET (not just FOLD) and drives a hand through every street to a
    // real showdown, over the wire. Can't assert on the COMPLETE/revealedHoleCards moment
    // itself - see the note below - so this asserts on what a client actually observes:
    // correct street progression, growing board, and total chips conserved end to end.
    @Test
    void twoClientsPlayAFullHandToShowdownOverTheWire() throws Exception {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        app = new PokerServer(table).start(0);
        String url = "ws://localhost:" + app.port() + "/ws";

        TestClient a = new TestClient(url);
        TestClient b = new TestClient(url);
        a.next(); // WELCOME
        b.next(); // WELCOME

        a.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"A\",\"amount\":1000}}");
        a.next();
        b.next();

        b.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"B\",\"amount\":1000}}");
        JsonNode aStarted = a.next();
        b.next();
        assertEquals("PREFLOP", aStarted.path("payload").path("round").path("stage").asText());
        long startingTotal = totalStacks(aStarted);

        // Preflop: A (seat 0, dealer/small blind) acts first heads-up, facing the big blind.
        a.send("{\"type\":\"CALL\",\"payload\":{}}");
        a.next();
        b.next();
        b.send("{\"type\":\"CHECK\",\"payload\":{}}");
        JsonNode afterPreflop = a.next();
        b.next();
        assertEquals("FLOP", afterPreflop.path("payload").path("round").path("stage").asText());
        assertEquals(3, afterPreflop.path("payload").path("round").path("board").size());

        // Postflop: the non-dealer (B) acts first every street.
        b.send("{\"type\":\"BET\",\"payload\":{\"amount\":20}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CALL\",\"payload\":{}}");
        JsonNode afterFlop = a.next();
        b.next();
        assertEquals("TURN", afterFlop.path("payload").path("round").path("stage").asText());
        assertEquals(4, afterFlop.path("payload").path("round").path("board").size());

        b.send("{\"type\":\"CHECK\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CHECK\",\"payload\":{}}");
        JsonNode afterTurn = a.next();
        b.next();
        assertEquals("RIVER", afterTurn.path("payload").path("round").path("stage").asText());
        assertEquals(5, afterTurn.path("payload").path("round").path("board").size());

        b.send("{\"type\":\"CHECK\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CHECK\",\"payload\":{}}");
        JsonNode aFinal = a.next();
        JsonNode bFinal = b.next();

        // The hand resolves at showdown and, since neither player busted, immediately chains
        // into a new hand inside the same apply() call - by the time this broadcast fires,
        // the COMPLETE-stage snapshot (with revealedHoleCards) already happened and passed.
        // No test can observe it over the wire as the server is built today; only the total
        // chip count is guaranteed stable across that whole sequence.
        assertEquals(startingTotal, totalStacks(aFinal));
        assertEquals(startingTotal, totalStacks(bFinal));
    }

    // Stacks alone aren't stable across this call - if the hand chained into a new one,
    // blinds are already posted for it, so whatever's in the new round's pot has to be added
    // back to make an apples-to-apples total, same as TableEngineTest's chip-conservation check.
    private static long totalStacks(JsonNode envelope) {
        long total = 0;
        for (JsonNode seat : envelope.path("payload").path("seats")) {
            JsonNode player = seat.path("player");
            if (!player.isNull() && !player.isMissingNode()) {
                total += player.path("stack").asLong();
            }
        }
        JsonNode round = envelope.path("payload").path("round");
        if (!round.isNull() && !round.isMissingNode()) {
            total += round.path("pot").asLong();
        }
        return total;
    }

    private static final class TestClient implements WebSocket.Listener {
        private final WebSocket socket;
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        TestClient(String url) throws Exception {
            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .get(5, TimeUnit.SECONDS);
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        JsonNode next() throws Exception {
            String raw = messages.poll(5, TimeUnit.SECONDS);
            if (raw == null) {
                throw new IllegalStateException("timed out waiting for a server message");
            }
            return MAPPER.readTree(raw);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
