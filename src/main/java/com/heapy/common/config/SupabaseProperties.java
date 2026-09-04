package com.heapy.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heapy.supabase")
public record SupabaseProperties(
        String url,
        String anonKey
) {
}
