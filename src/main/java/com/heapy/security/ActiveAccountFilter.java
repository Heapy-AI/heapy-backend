package com.heapy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** 탈퇴 계정은 만료 전 JWT로도 API에 다시 접근할 수 없다. @author 김진우 */
public class ActiveAccountFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;

    public ActiveAccountFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            try {
                UUID user = UUID.fromString(token.getToken().getSubject());
                UUID session = UUID.fromString(token.getToken().getClaimAsString("session_id"));
                boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from auth.users u join auth.sessions s on s.user_id=u.id where u.id=? and s.id=?)",
                        Boolean.class, user, session));
                if (!exists) {
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            } catch (DataAccessException exception) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
