package com.savuliak.userservice.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.savuliak.userservice.config.InternalApiProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilterRegistration(
            InternalApiProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalApiKeyFilter(properties, objectMapper));
        registration.addUrlPatterns("/api/v1/users/internal/*");
        registration.setName("internalApiKeyFilter");
        return registration;
    }
}
