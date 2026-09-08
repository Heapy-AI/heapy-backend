package com.heapy.checkup;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heapy.ocr")
public record OcrProperties(boolean enabled, String region, String accountId, String bucket, String workerArn) {
}
