package com.heapy.user.service;

import com.heapy.checkup.OcrGateway;
import com.heapy.checkup.OcrRepository;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.analysis.HealthAnalysisCache;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 계정과 업무 데이터를 함께 삭제하고 외부 정리 실패 시 완료 응답을 막는다.
 *
 * @author 김진우
 */
@Service
public class AccountWithdrawalService {
    private final JdbcTemplate jdbc;
    private final OcrRepository ocr;
    private final OcrGateway gateway;
    private final HealthAnalysisCache cache;

    public AccountWithdrawalService(JdbcTemplate jdbc, OcrRepository ocr,
            OcrGateway gateway, HealthAnalysisCache cache) {
        this.jdbc = jdbc;
        this.ocr = ocr;
        this.gateway = gateway;
        this.cache = cache;
    }

    @Transactional(timeout = 120)
    public void withdraw(UUID user) {
        // 작성자: 김진우 — 동일 사용자 삭제와 캐시 쓰기를 직렬화한다. 다른 사용자 행은 잠그지 않는다.
        var accounts = jdbc.query("select id from auth.users where id=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), user);
        if (accounts.isEmpty()) return;
        jdbc.query("select user_id from public.users where user_id=? for update",
                (rs, row) -> rs.getObject(1, UUID.class), user);

        Boolean processing = jdbc.queryForObject("""
                select exists(select 1 from public.ocr_jobs where user_id=?
                    and status in ('pending','processing') and expires_at>current_timestamp)
                """, Boolean.class, user);
        if (Boolean.TRUE.equals(processing)) throw new HeapyException(ErrorCode.WITHDRAWAL_BUSY);
        // 작성자: 김진우 — 현재 앱은 S3를 사용한다. 미지원 저장소 객체가 있으면 누락한 채 성공시키지 않는다.
        Boolean storageOwned = jdbc.queryForObject("""
                select exists(select 1 from storage.objects where owner_id=?)
                """, Boolean.class, user.toString());
        if (Boolean.TRUE.equals(storageOwned)) throw new HeapyException(ErrorCode.WITHDRAWAL_STORAGE_UNSUPPORTED);

        var jobs = ocr.forWithdrawal(user);
        Long jobCount = jdbc.queryForObject("select count(*) from public.ocr_jobs where user_id=?", Long.class, user);
        if (jobCount == null || jobCount != jobs.size()) {
            throw new HeapyException(ErrorCode.WITHDRAWAL_STORAGE_UNSUPPORTED);
        }
        for (var job : jobs) gateway.purgeForWithdrawal(job);
        cache.evictUser(user);

        // 작성자: 김진우 — CASCADE가 없는 참조와 RESTRICT 참조를 자식부터 제거한다.
        jdbc.update("delete from public.equipped_items where user_id=?", user);
        jdbc.update("delete from public.user_inventory where user_id=?", user);
        jdbc.update("delete from public.coin_ledger where user_id=?", user);
        jdbc.update("delete from public.shop_purchases where user_id=?", user);
        jdbc.update("delete from public.medication_intakes where user_id=?", user);
        jdbc.update("delete from private.signup_requests where user_id=?", user);
        jdbc.update("delete from public.users where user_id=?", user);
        jdbc.update("delete from auth.refresh_tokens where user_id=?", user.toString());
        jdbc.update("delete from auth.flow_state where user_id=? or linking_target_id=?", user, user);
        jdbc.update("delete from auth.scim_users where user_id=?", user);
        jdbc.update("delete from auth.audit_log_entries where payload->>'actor_id'=?", user.toString());
        // 작성자: 김진우 — 같은 DB 트랜잭션으로 인증 계정·세션·갱신 토큰도 삭제한다.
        jdbc.update("delete from auth.users where id=?", user);
    }
}
