package com.pokerproject.protocol;

import com.pokerproject.domain.Card;
import com.pokerproject.domain.GameRound;
import com.pokerproject.domain.Player;
import com.pokerproject.domain.PlayerStatus;
import com.pokerproject.domain.RoundStage;
import com.pokerproject.domain.Seat;
import com.pokerproject.domain.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Translates a Table's live domain state into what one specific viewer is allowed to see.
// This is the only place that decides who's allowed to see whose hole cards - Table itself
// never redacts anything, so there's only one source of truth for game state.
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    // connectedPlayerIds: playerIds with a currently-open WebSocket, straight from
    // PokerServer's connection registry - Table itself has no notion of connections.
    public static TableSnapshot build(Table table, UUID viewerId, Set<UUID> connectedPlayerIds) {
        List<SeatSnapshot> seats = new ArrayList<>();
        for (Seat seat : table.seats()) {
            Player p = seat.player();
            PlayerSnapshot playerSnapshot = (p == null) ? null
                    : new PlayerSnapshot(p.id(), p.displayName(), p.stack(), p.totalBuyIn(), p.status(),
                            connectedPlayerIds.contains(p.id()));
            seats.add(new SeatSnapshot(seat.index(), playerSnapshot));
        }

        RoundSnapshot round = buildRound(table, table.currentRound(), viewerId);
        return new TableSnapshot(seats, table.dealerSeat(), table.isClosed(), round,
                table.variant(), table.votingOpen(), table.votes());
    }

    private static RoundSnapshot buildRound(Table table, GameRound round, UUID viewerId) {
        if (round == null) {
            return null;
        }

        List<Card> yourHoleCards = round.holeCards().containsKey(viewerId)
                ? round.holeCards().get(viewerId).cards()
                : List.of();

        Map<UUID, List<Card>> revealed = new HashMap<>();
        if (round.stage() == RoundStage.COMPLETE) {
            for (Seat seat : table.seats()) {
                Player p = seat.player();
                if (p != null && p.status() != PlayerStatus.FOLDED && round.holeCards().containsKey(p.id())) {
                    revealed.put(p.id(), round.holeCards().get(p.id()).cards());
                }
            }
        }

        return new RoundSnapshot(round.stage(), round.board(), round.pot().total(),
                round.smallBlindSeat(), round.bigBlindSeat(), round.actingSeat(), round.currentBet(),
                yourHoleCards, revealed, round.contributionThisStreet(viewerId), round.bestFiveByPlayer(),
                round.winners());
    }
}
