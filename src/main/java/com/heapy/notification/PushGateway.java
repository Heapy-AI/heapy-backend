package com.heapy.notification;

import java.util.Map;

/** 푸시 사업자 결과만 반환하며 기기 토큰을 로그에 기록하지 않는다. @author 김진우 */
public interface PushGateway {
    record Result(String messageId, String failure, boolean invalidToken) {
        public boolean sent() { return messageId != null; }
    }
    boolean enabled();
    Result send(String token, Map<String, String> data);
}
