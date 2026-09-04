package com.heapy.security;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new HeapyException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
}
