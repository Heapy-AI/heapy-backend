package com.heapy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeapyBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeapyBackendApplication.class, args);
    }
}
