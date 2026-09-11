package com.heapy.checkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heapy.common.exception.HeapyException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 합성 회차로 소유권·정렬·커서·소견 전용 회차를 검증한다. @author 김진우 */
class CheckupHistoryServiceTest {
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private CheckupHistoryService service;
    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void prepare() {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            create table health_checkup_records(record_id uuid primary key,user_id uuid,measured_at date,
            provider_name text,source_type text,confirmed_at timestamp with time zone)
            """);
        jdbc.execute("create table health_checkup_results(record_id uuid,item_code text)");
        service = new CheckupHistoryService(jdbc);
    }

    @AfterEach
    void close() { source.close(); }

    private UUID record(UUID owner, int id, String date) {
        UUID key = UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d", id));
        jdbc.update("insert into health_checkup_records values(?,?,?,'합성 기관','ocr',null)", key, owner,
                date == null ? null : Date.valueOf(date));
        return key;
    }

    @Test
    void 본인_기록만_조회하고_일반결과만_집계한다() {
        UUID own = record(user,1,"2026-09-01");
        record(other,2,"2026-09-02");
        jdbc.update("insert into health_checkup_results values(?,'SYNTHETIC_A'),(?,'SYNTHETIC_B')",own,own);
        var page = service.list(user,20,null);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().getFirst().recordId()).isEqualTo(own);
        assertThat(page.records().getFirst().resultCount()).isEqualTo(2);
        assertThat(page.meta().hasNext()).isFalse();
    }

    @Test
    void 같은날_회차와_날짜없는_회차를_중복없이_이어본다() {
        UUID first=record(user,3,"2026-09-01"), second=record(user,2,"2026-09-01"),
                third=record(user,1,"2025-01-01"), fourth=record(user,5,null), fifth=record(user,4,null);
        var page=service.list(user,2,null);
        assertThat(page.records()).extracting(CheckupHistoryService.RecordSummary::recordId).containsExactly(first,second);
        var next=service.list(user,2,page.meta().nextCursor());
        assertThat(next.records()).extracting(CheckupHistoryService.RecordSummary::recordId).containsExactly(third,fourth);
        var last=service.list(user,2,next.meta().nextCursor());
        assertThat(last.records()).extracting(CheckupHistoryService.RecordSummary::recordId).containsExactly(fifth);
        assertThat(last.meta().nextCursor()).isNull();
        assertThat(last.meta().hasNext()).isFalse();
    }

    @Test
    void 소견전용_회차와_확정시각없는_기존회차도_유지한다() {
        record(user,1,null);
        var row=service.list(user,100,null).records().getFirst();
        assertThat(row.resultCount()).isZero();
        assertThat(row.confirmedAt()).isNull();
        assertThat(row.measuredAt()).isNull();
    }

    @Test
    void 빈목록은_성공하고_다음커서가_없다() {
        var page=service.list(user,20,null);
        assertThat(page.records()).isEmpty();
        assertThat(page.meta()).isEqualTo(new CheckupHistoryService.PageMeta(null,false,20));
    }

    @Test
    void 잘못된_제한과_커서와_타사용자_커서를_거절한다() {
        for (int limit : new int[]{0,-1,101})
            assertThatThrownBy(()->service.list(user,limit,null)).isInstanceOf(HeapyException.class);
        for (String cursor : new String[]{"", "invalid!!", "x".repeat(257)})
            assertThatThrownBy(()->service.list(user,20,cursor)).isInstanceOf(HeapyException.class);
        record(user,1,"2026-09-01"); record(user,2,"2026-09-01");
        String cursor=service.list(user,1,null).meta().nextCursor();
        assertThatThrownBy(()->service.list(other,20,cursor)).isInstanceOf(HeapyException.class);
    }
}
