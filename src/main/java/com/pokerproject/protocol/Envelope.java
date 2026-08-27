package com.pokerproject.protocol;

import com.fasterxml.jackson.databind.JsonNode;

// The wire message shape, both directions. Client->server "type" is a TableCommand or
// ActionType name; server->client "type" is WELCOME / STATE / ERROR. payload is loosely
// typed on purpose (Option A) - one envelope shape for every message instead of a sealed
// type per message.
public record Envelope(String type, JsonNode payload) {
}
