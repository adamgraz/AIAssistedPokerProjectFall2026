package com.pokerproject.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class Pot {

    private final Map<UUID, Long> contributions = new HashMap<>();

    public void contribute(UUID playerId, long amount) {
        if (contributions.containsKey(playerId)) {
            contributions.put(playerId, contributions.get(playerId) + amount);
        } else {
            contributions.put(playerId, amount);
        }
    }

    public long total() {
        return contributions.values().stream().mapToLong(Long::longValue).sum();
    }

    // Layered-contribution method: one pot per distinct contribution level. Folded players'
    // chips still count toward a tier's amount (their money is in the pot) but they're
    // excluded from eligiblePlayers (they can't win it).
    public List<SidePot> resolve(Set<UUID> folded) {
        List<Long> levels = contributions.values().stream().distinct().sorted().toList();

        List<SidePot> pots = new ArrayList<>();
        long previousLevel = 0;
        for (long level : levels) {
            List<UUID> reachedThisLevel = contributions.entrySet().stream()
                    .filter(e -> e.getValue() >= level)
                    .map(Map.Entry::getKey)
                    .toList();
            long tierAmount = (level - previousLevel) * reachedThisLevel.size();
            Set<UUID> eligible = reachedThisLevel.stream()
                    .filter(p -> !folded.contains(p))
                    .collect(Collectors.toSet());
            pots.add(new SidePot(tierAmount, eligible));
            previousLevel = level;
        }
        return pots;
    }

    public record SidePot(long amount, Set<UUID> eligiblePlayers) {
    }
}
