package com.heapy.medication;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 앱 실행과 무관하게 7일 예정 회차와 지연 상태를 보충한다. @author 김진우 */
@Component
public class MedicationWorker {
    private static final Logger log=LoggerFactory.getLogger(MedicationWorker.class);
    private final JdbcTemplate jdbc;
    private final MedicationService service;
    public MedicationWorker(JdbcTemplate jdbc,MedicationService service){this.jdbc=jdbc;this.service=service;}
    @Scheduled(fixedDelay=60000,initialDelay=30000)
    public void replenish(){
        try {
            UUID cursor=new UUID(0,0);
            while(true){
                var users=jdbc.query("""
                        select distinct user_id from public.user_medications where user_id>? and
                        (status='active' or exists(select 1 from public.medication_intakes i where i.user_id=user_medications.user_id and i.status='pending'))
                        order by user_id limit 100
                        """,(rs,row)->rs.getObject(1,UUID.class),cursor);
                if(users.isEmpty())return;
                for(UUID user:users)service.replenish(user);
                cursor=users.getLast();
            }
        }catch(Exception exception){log.warn("복약 일정 보충 재시도 필요: exceptionType={}",exception.getClass().getSimpleName());}
    }
}
