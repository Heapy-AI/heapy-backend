package com.heapy.security;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 탈퇴 뒤 남은 JWT의 접근 차단을 검증한다. @author 김진우 */
class ActiveAccountFilterTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void deletedAccountCannotReuseUnexpiredToken() throws Exception {
        UUID user = UUID.randomUUID();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user), any(UUID.class))).thenReturn(false);
        var jwt = Jwt.withTokenValue("검증된토큰").header("alg", "RS256").subject(user.toString())
                .claim("session_id", UUID.randomUUID().toString()).build();
        var authentication = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("GET", "/api/users/me");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new ActiveAccountFilter(jdbc).doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void activeAccountContinuesNormally() throws Exception {
        UUID user = UUID.randomUUID();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user), any(UUID.class))).thenReturn(true);
        var jwt = Jwt.withTokenValue("검증된토큰").header("alg", "RS256").subject(user.toString())
                .claim("session_id", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        var request = new MockHttpServletRequest("GET", "/api/users/me");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new ActiveAccountFilter(jdbc).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }
}
