package com.heapy.user.service;

import com.heapy.checkup.OcrGateway;
import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrRepository;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.analysis.HealthAnalysisCache;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 삭제 순서, 사용자 범위와 외부 정리 실패를 검증한다. @author 김진우 */
class AccountWithdrawalServiceTest {
    private final UUID user = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OcrRepository ocr = mock(OcrRepository.class);
    private final OcrGateway gateway = mock(OcrGateway.class);
    private final HealthAnalysisCache cache = mock(HealthAnalysisCache.class);
    private final AccountWithdrawalService service = new AccountWithdrawalService(jdbc, ocr, gateway, cache);

    @BeforeEach
    void setUp() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any(), eq(user)))
                .thenReturn(List.of(user));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user.toString()))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(user))).thenReturn(0L);
        when(ocr.forWithdrawal(user)).thenReturn(List.of());
    }

    @Test
    void deletesOnlyAuthenticatedUserInDependencyOrder() {
        service.withdraw(user);
        var order = inOrder(cache, jdbc);
        order.verify(cache).evictUser(user);
        for (String table : List.of("public.equipped_items", "public.user_inventory", "public.coin_ledger",
                "public.shop_purchases", "public.medication_intakes", "private.signup_requests", "public.users")) {
            order.verify(jdbc).update("delete from " + table + " where user_id=?", user);
        }
        order.verify(jdbc).update("delete from auth.refresh_tokens where user_id=?", user.toString());
        order.verify(jdbc).update("delete from auth.flow_state where user_id=? or linking_target_id=?", user, user);
        order.verify(jdbc).update("delete from auth.scim_users where user_id=?", user);
        order.verify(jdbc).update("delete from auth.audit_log_entries where payload->>'actor_id'=?", user.toString());
        order.verify(jdbc).update("delete from auth.users where id=?", user);
    }

    @Test
    void processingDocumentDoesNotDeleteAnything() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(user))).thenReturn(true);
        assertThrows(HeapyException.class, () -> service.withdraw(user));
        verifyNoInteractions(gateway, cache);
        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }

    @Test
    void missingOcrMetadataFailsClosed() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(user))).thenReturn(1L);
        assertThrows(HeapyException.class, () -> service.withdraw(user));
        verifyNoInteractions(gateway, cache);
        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }

    @Test
    void externalPurgeFailurePreservesDatabase() {
        var job = new Job(UUID.randomUUID(), user, "image", "review", 1, null,
                Instant.now(), Instant.now().plusSeconds(600), "jpg", 12L, "hash", "health_checkup");
        when(ocr.forWithdrawal(user)).thenReturn(List.of(job));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(user))).thenReturn(1L);
        doThrow(new IllegalStateException("외부 정리 실패")).when(gateway).purgeForWithdrawal(job);
        assertThrows(IllegalStateException.class, () -> service.withdraw(user));
        verifyNoInteractions(cache);
        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }

    @Test
    void redisFailurePreservesAccountForRetry() {
        doThrow(new IllegalStateException("캐시 정리 실패")).when(cache).evictUser(user);
        assertThrows(IllegalStateException.class, () -> service.withdraw(user));
        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }

    @Test
    void alreadyDeletedAccountIsIdempotent() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any(), eq(user))).thenReturn(List.of());
        service.withdraw(user);
        verifyNoInteractions(ocr, gateway, cache);
        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }
}
