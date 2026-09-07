package com.heapy.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.heapy.auth.client.SupabaseSignupClient;
import com.heapy.auth.client.SupabaseSignupClient.SignupResult;
import com.heapy.auth.dto.SignupRequest;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SignupServiceTest {
    private final SignupRequest request = new SignupRequest("Test@Example.COM", "Strong123!");
    private final SupabaseSignupClient client = mock(SupabaseSignupClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SignupService service = new SignupService(client, jdbc);

    private void 저장된_요청(UUID userId, String password) throws Exception {
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((request.email() + "\u0000" + password).getBytes(StandardCharsets.UTF_8)));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(new BCryptPasswordEncoder().encode(fingerprint));
        when(rs.getObject(2, UUID.class)).thenReturn(userId);
        when(rs.getBoolean(3)).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenAnswer(call -> ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
    }

    @Test
    void 성공한_요청을_재전송하면_메일을_다시_보내지_않는다() throws Exception {
        UUID userId = UUID.randomUUID();
        저장된_요청(userId, request.password());
        assertThat(service.signup(request, UUID.randomUUID()).userId()).isEqualTo(userId);
        verifyNoInteractions(client);
    }

    @Test
    void 같은_키의_다른_본문은_충돌이다() throws Exception {
        저장된_요청(UUID.randomUUID(), "Different123!");
        assertThatThrownBy(() -> service.signup(request, UUID.randomUUID()))
                .isInstanceOfSatisfying(HeapyException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(client);
    }

    @Test
    void 신규_요청은_프로필_생성과_성공결과_저장을_수행한다() throws Exception {
        저장된_요청(null, request.password());
        UUID userId = UUID.randomUUID();
        when(client.signup(request)).thenReturn(new SignupResult(userId, false));
        assertThat(service.signup(request, UUID.randomUUID()).nextStep()).isEqualTo("login");
        verify(jdbc).update(contains("insert into public.users"), eq(userId));
        verify(jdbc).update(contains("set user_id"), eq(userId), eq(false), any(UUID.class));
    }
}
