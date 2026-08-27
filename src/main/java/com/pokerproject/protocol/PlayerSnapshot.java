package com.pokerproject.protocol;

import com.pokerproject.domain.PlayerStatus;

import java.util.UUID;

public record PlayerSnapshot(UUID id, String displayName, long stack, PlayerStatus status) {
}
