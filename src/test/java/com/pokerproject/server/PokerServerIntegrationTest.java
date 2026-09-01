package com.pokerproject.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokerproject.db.DB;
import com.pokerproject.domain.GameConfig;
import com.pokerproject.domain.Table;
import com.pokerproject.protocol.GameVariant;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Proves the wire layer itself - message parsing, dispatch onto Table.apply(), per-viewer
// broadcast, and hole-card redaction. Full hand correctness (side pots, showdown, etc.) is
// already proven against Table directly in TableEngineTest; re-proving that over a socket
// would just be slower, not more convincing.
class PokerServerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // A completed hand or a vacated seat now touches the database (Profile.findByUuid, see
    // PokerServer's onHandComplete/onPlayerLeftSeat hooks) - the schema has to exist before
    // that first happens, or those hooks throw on a missing table instead of a clean
    // empty-Optional guest case.
    @BeforeAll
    static void useTestDatabase() {
        DB.useTestMode();
        DB.reset();
    }

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
        JsonNode aBothSeated = a.next();
        JsonNode bBothSeated = b.next();
        assertTrue(aBothSeated.path("payload").path("round").isNull()); // still nothing dealt
        assertTrue(bBothSeated.path("payload").path("round").isNull());

        // Nothing ever auto-starts, not even the first hand of the night - everyone gets a
        // chance to join before it deals.
        a.send("{\"type\":\"NEXT_HAND\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next();
        b.next();
        b.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
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

        // The fold resolves the hand uncontested. Table fires onHandComplete mid-apply() (the
        // COMPLETE-stage snapshot itself), then PokerServer's own post-apply broadcast fires
        // too - both show the same COMPLETE state now that hands no longer auto-chain.
        JsonNode aComplete = a.next();
        JsonNode bComplete = b.next();
        assertEquals("COMPLETE", aComplete.path("payload").path("round").path("stage").asText());
        assertEquals("COMPLETE", bComplete.path("payload").path("round").path("stage").asText());
        // Nobody's cards get shown for an uncontested fold win, not even the winner's -
        // hands were never compared, same as a real table.
        assertTrue(aComplete.path("payload").path("round").path("revealedHoleCards").isEmpty());
        assertTrue(bComplete.path("payload").path("round").path("revealedHoleCards").isEmpty());
        a.next();
        b.next();

        // Nothing deals until a player calls NEXT_HAND and a vote decides the mode.
        a.send("{\"type\":\"NEXT_HAND\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next();
        b.next();
        b.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        JsonNode aNext = a.next();
        JsonNode bNext = b.next();

        // Both clients voted the same way, so that decides and deals immediately - both
        // should see a fresh PREFLOP round with blinds already posted.
        assertEquals("PREFLOP", aNext.path("payload").path("round").path("stage").asText());
        assertEquals("PREFLOP", bNext.path("payload").path("round").path("stage").asText());
        assertEquals(15, aNext.path("payload").path("round").path("pot").asLong());
    }

    // Exercises CALL/CHECK/BET (not just FOLD) and drives a hand through every street to a
    // real showdown, over the wire - including the COMPLETE-stage snapshot itself (revealed
    // hole cards), which Table's onHandComplete hook broadcasts before chaining into the next
    // hand.
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
        a.next();
        b.next();

        // Nothing ever auto-starts, not even the first hand of the night.
        a.send("{\"type\":\"NEXT_HAND\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next();
        b.next();
        b.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
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
        assertEquals(3, afterPreflop.path("payload").path("round").path("boards").get(0).size());

        // Postflop: the non-dealer (B) acts first every street.
        b.send("{\"type\":\"BET\",\"payload\":{\"amount\":20}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CALL\",\"payload\":{}}");
        JsonNode afterFlop = a.next();
        b.next();
        assertEquals("TURN", afterFlop.path("payload").path("round").path("stage").asText());
        assertEquals(4, afterFlop.path("payload").path("round").path("boards").get(0).size());

        b.send("{\"type\":\"CHECK\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CHECK\",\"payload\":{}}");
        JsonNode afterTurn = a.next();
        b.next();
        assertEquals("RIVER", afterTurn.path("payload").path("round").path("stage").asText());
        assertEquals(5, afterTurn.path("payload").path("round").path("boards").get(0).size());

        b.send("{\"type\":\"CHECK\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"CHECK\",\"payload\":{}}");

        // The hand resolves at showdown - this first broadcast is the COMPLETE-stage snapshot,
        // with both hole cards revealed since neither player folded, and each player's winning
        // 5-card hand exposed for the showdown UI to highlight.
        JsonNode aComplete = a.next();
        JsonNode bComplete = b.next();
        assertEquals("COMPLETE", aComplete.path("payload").path("round").path("stage").asText());
        assertEquals(2, aComplete.path("payload").path("round").path("revealedHoleCards").size());
        assertEquals(2, bComplete.path("payload").path("round").path("revealedHoleCards").size());
        JsonNode board0BestFive = aComplete.path("payload").path("round").path("bestFiveByBoard").get(0);
        assertEquals(2, board0BestFive.size());
        for (JsonNode five : board0BestFive) {
            assertEquals(5, five.size());
        }
        a.next(); // PokerServer's own post-apply broadcast, same COMPLETE state again
        b.next();

        // Nothing deals until a player calls NEXT_HAND and a vote decides the mode - since
        // neither player busted, this still stays heads-up.
        a.send("{\"type\":\"NEXT_HAND\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next();
        b.next();
        b.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        JsonNode aFinal = a.next();
        JsonNode bFinal = b.next();
        assertEquals(startingTotal, totalStacks(aFinal));
        assertEquals(startingTotal, totalStacks(bFinal));
    }

    // Proves the one piece of real business logic PokerServer has: REMOVE_PLAYER is only ever
    // let through to Table while the target's connection is still live-checked as gone. A live
    // player can never be kicked; only once their socket actually closes does it work.
    @Test
    void removePlayerOnlyWorksAfterTheTargetsConnectionCloses() throws Exception {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        app = new PokerServer(table).start(0);
        String url = "ws://localhost:" + app.port() + "/ws";

        TestClient a = new TestClient(url);
        TestClient b = new TestClient(url);
        a.next(); // WELCOME
        JsonNode bWelcome = b.next();
        String bId = bWelcome.path("payload").path("playerId").asText();

        a.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"A\",\"amount\":1000}}");
        a.next();
        b.next();
        b.send("{\"type\":\"SIT_DOWN\",\"payload\":{\"displayName\":\"B\",\"amount\":1000}}");
        a.next();
        b.next();

        // Nothing ever auto-starts, not even the first hand of the night.
        a.send("{\"type\":\"NEXT_HAND\",\"payload\":{}}");
        a.next();
        b.next();
        a.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next();
        b.next();
        b.send("{\"type\":\"VOTE_GAME_MODE\",\"payload\":{\"mode\":\"TEXAS_HOLDEM\"}}");
        a.next(); // PREFLOP
        b.next();

        // Fold hand 1 uncontested (COMPLETE has its own broadcast pair, same as the other
        // tests) so what follows is a clean between-hands removal, not tangled up with
        // Table's own forced-fold bookkeeping for a still-live hand - that's covered directly
        // in RemovePlayerTest.
        a.send("{\"type\":\"FOLD\",\"payload\":{}}");
        a.next();
        b.next();
        a.next();
        b.next();

        // B is still connected - A can't remove them.
        a.send("{\"type\":\"REMOVE_PLAYER\",\"payload\":{\"targetPlayerId\":\"" + bId + "\"}}");
        JsonNode rejected = a.next();
        assertEquals("ERROR", rejected.path("type").asText());

        // B disconnects for real - still seated, just orphaned now, same as a browser tab
        // being closed. onClose only drops the connection registry entry, not the seat itself.
        b.close();

        // The registry update happens on Javalin's own thread, asynchronously to this test -
        // retry until it's visibly taken effect rather than assuming a fixed delay is enough.
        JsonNode afterKick = null;
        for (int i = 0; i < 20 && afterKick == null; i++) {
            a.send("{\"type\":\"REMOVE_PLAYER\",\"payload\":{\"targetPlayerId\":\"" + bId + "\"}}");
            JsonNode result = a.next();
            if (!"ERROR".equals(result.path("type").asText())) {
                afterKick = result;
            } else {
                Thread.sleep(50);
            }
        }
        assertNotNull(afterKick, "REMOVE_PLAYER never succeeded after B's connection closed");

        boolean bStillSeated = false;
        for (JsonNode seat : afterKick.path("payload").path("seats")) {
            JsonNode player = seat.path("player");
            if (!player.isNull() && !player.isMissingNode() && bId.equals(player.path("id").asText())) {
                bStillSeated = true;
            }
        }
        assertFalse(bStillSeated);
    }

    // Proves the login/guest-upgrade path end to end: CREATE_PROFILE isn't a gate (the
    // connection was already a working guest before it), LOGIN from a fresh connection reclaims
    // the same identity by passphrase alone, and a second simultaneous LOGIN for that same
    // profile is rejected rather than letting two connections share one identity.
    @Test
    void createProfileThenLoginReclaimsTheSameIdentityAndBlocksASimultaneousSecondLogin() throws Exception {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        app = new PokerServer(table).start(0);
        String url = "ws://localhost:" + app.port() + "/ws";

        TestClient a = new TestClient(url);
        a.next(); // WELCOME - guest identity, about to be upgraded

        a.send("{\"type\":\"CREATE_PROFILE\",\"payload\":{\"passphrase\":\"hunter2\",\"displayName\":\"Alice\"}}");
        JsonNode created = a.next();
        assertEquals("WELCOME", created.path("type").asText());
        assertEquals("Alice", created.path("payload").path("displayName").asText());
        assertEquals(0, created.path("payload").path("handsPlayed").asLong());
        String profileUuid = created.path("payload").path("playerId").asText();

        // A duplicate CREATE_PROFILE for the same passphrase is rejected - the create-race
        // guard (synchronized(table) around check-then-insert) covers this even single-threaded.
        TestClient dupe = new TestClient(url);
        dupe.next();
        dupe.send("{\"type\":\"CREATE_PROFILE\",\"payload\":{\"passphrase\":\"hunter2\",\"displayName\":\"Someone Else\"}}");
        assertEquals("ERROR", dupe.next().path("type").asText());
        dupe.close();

        a.close();

        // A fresh connection logging in with the same passphrase reclaims the same identity -
        // retried the same way REMOVE_PLAYER's close-then-act test above does, since the
        // registry update happens on Javalin's own thread, asynchronously to this test.
        TestClient reconnected = new TestClient(url);
        reconnected.next(); // WELCOME - a new guest identity for this socket, about to be replaced
        JsonNode loggedIn = null;
        for (int i = 0; i < 20 && loggedIn == null; i++) {
            reconnected.send("{\"type\":\"LOGIN\",\"payload\":{\"passphrase\":\"hunter2\"}}");
            JsonNode result = reconnected.next();
            if (!"ERROR".equals(result.path("type").asText())) {
                loggedIn = result;
            } else {
                Thread.sleep(50);
            }
        }
        assertNotNull(loggedIn, "LOGIN never succeeded after the original connection closed");
        assertEquals(profileUuid, loggedIn.path("payload").path("playerId").asText());

        // While that connection is still live, a second LOGIN for the same profile is rejected -
        // not just a second CREATE_PROFILE.
        TestClient intruder = new TestClient(url);
        intruder.next();
        intruder.send("{\"type\":\"LOGIN\",\"payload\":{\"passphrase\":\"hunter2\"}}");
        assertEquals("ERROR", intruder.next().path("type").asText());
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

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
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
