package com.orderflow.auth.dto;

import com.orderflow.auth.domain.Role;
import com.orderflow.auth.domain.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String username,
        String email,
        Set<Role> roles,
        Instant createdAt) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getRoles(), user.getCreatedAt());
    }
}
