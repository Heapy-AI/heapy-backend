package com.heapy.health.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** DB·임시 저장소 준비 후에만 자정 작업을 활성화한다. @author 김진우 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "heapy.health.analysis.enabled", havingValue = "true")
public class HealthAnalysisConfiguration { }
