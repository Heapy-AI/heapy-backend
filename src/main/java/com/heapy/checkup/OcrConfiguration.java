package com.heapy.checkup;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class OcrConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock ocrClock() { return Clock.systemUTC(); }
}
