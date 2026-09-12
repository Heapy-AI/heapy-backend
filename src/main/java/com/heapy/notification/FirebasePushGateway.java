package com.heapy.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 서버 자격 증명은 환경의 ADC에서 읽고 앱에는 배포하지 않는다. @author 김진우 */
@Component
public class FirebasePushGateway implements PushGateway {
    private final boolean enabled;
    private final String projectId;
    private FirebaseMessaging messaging;

    public FirebasePushGateway(@Value("${heapy.push.enabled:false}") boolean enabled,
            @Value("${heapy.push.project-id:}") String projectId) {
        this.enabled = enabled;
        this.projectId = projectId;
    }

    @Override public boolean enabled() { return enabled && !projectId.isBlank(); }

    private synchronized FirebaseMessaging client() throws IOException {
        if (messaging == null) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId).setConnectTimeout(5000).setReadTimeout(5000).build();
            messaging = FirebaseMessaging.getInstance(FirebaseApp.initializeApp(options, "heapy-push"));
        }
        return messaging;
    }

    @Override public Result send(String token, Map<String, String> data) {
        if (!enabled || projectId.isBlank()) return new Result(null, "PUSH_NOT_CONFIGURED", false);
        try {
            Message message = Message.builder().setToken(token).putAllData(data)
                    .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(15 * 60 * 1000).build()).build();
            return new Result(client().send(message), null, false);
        } catch (FirebaseMessagingException exception) {
            MessagingErrorCode code = exception.getMessagingErrorCode();
            return new Result(null, code == null ? "PROVIDER_ERROR" : code.name(),
                    code == MessagingErrorCode.UNREGISTERED);
        } catch (IOException | IllegalArgumentException exception) {
            return new Result(null, "PUSH_CONFIGURATION_ERROR", false);
        }
    }
}
