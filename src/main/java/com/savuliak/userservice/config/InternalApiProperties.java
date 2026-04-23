package com.savuliak.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal")
public record InternalApiProperties(String apiKey) {
}
