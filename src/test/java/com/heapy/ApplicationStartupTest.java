package com.heapy;

import com.heapy.auth.controller.LogoutController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 웹 서버 기동과 URL 중복 여부를 외부 서비스 없이 검증한다. @author 김진우 */
class ApplicationStartupTest {
    @Test
    void startsWithSingleLogoutMappingAndHealthyEndpoint() throws Exception {
        try (var context = new SpringApplicationBuilder(HeapyBackendApplication.class).run(
                "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:h2:mem:startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=none", "--spring.flyway.enabled=false",
                "--server.address=127.0.0.1", "--server.port=0",
                "--heapy.ocr.enabled=false", "--heapy.push.enabled=false",
                "--heapy.chat.enabled=false", "--heapy.health.analysis.enabled=false",
                "--management.health.redis.enabled=false")) {
            var mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            var handlers = mapping.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getKey().getPatternValues().contains("/api/auth/logout"))
                    .filter(entry -> entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.POST))
                    .toList();
            assertEquals(1, handlers.size());
            assertEquals(LogoutController.class, handlers.getFirst().getValue().getBeanType());
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"UP\""));
            }
        }
    }
}
