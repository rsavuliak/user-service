package com.savuliak.userservice.config;

import com.savuliak.userservice.security.CurrentUserArgumentResolver;
import com.savuliak.userservice.security.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Single source of truth for which paths the JWT interceptor runs on.
 * Internal (/internal/**) and public-profile (/{id}/public) endpoints are
 * excluded here so the interceptor body itself has no path logic.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebConfig(JwtAuthInterceptor jwtAuthInterceptor,
                     CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/v1/users/**")
                .excludePathPatterns(
                        "/api/v1/users/internal/**",
                        "/api/v1/users/*/public");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
