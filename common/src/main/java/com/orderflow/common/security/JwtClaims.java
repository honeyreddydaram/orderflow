package com.orderflow.common.security;

import java.util.List;
import java.util.UUID;

/** Minimal set of claims resource-server filters need out of a verified access token. */
public record JwtClaims(UUID userId, String username, List<String> roles) {
}
