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
