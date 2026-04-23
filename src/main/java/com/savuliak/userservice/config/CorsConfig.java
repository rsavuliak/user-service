package com.savuliak.userservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS is for browsers only. Internal endpoints (/api/v1/users/internal/**)
 * are server-to-server and intentionally not mapped — adding CORS headers
 * there would be noise and could mask misconfiguration.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = corsProperties.allowedOrigins().toArray(new String[0]);

        registry.addMapping("/api/v1/users/me")
                .allowedOrigins(origins)
                .allowedMethods("GET", "PATCH", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(true);

        registry.addMapping("/api/v1/users/*/public")
                .allowedOrigins(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(true);
    }
}
