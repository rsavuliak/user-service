package com.savuliak.userservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.savuliak.userservice.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Validates the JWT cookie on every request routed to this interceptor.
 * Path scope is determined solely by WebConfig#addInterceptors — this class
 * has no path logic of its own.
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_USER_ATTR = "AUTHENTICATED_USER";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    private final JwtTokenParser parser;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public JwtAuthInterceptor(JwtTokenParser parser,
                              AppProperties appProperties,
                              ObjectMapper objectMapper) {
        this.parser = parser;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        // CORS preflight requests carry no cookies and are not data traffic.
        // Let Spring's CORS machinery handle them without an auth challenge.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = readCookie(request, appProperties.cookieName());
        if (token == null) {
            return unauthorized(response, "Missing token cookie");
        }
        try {
            AuthenticatedUser user = parser.parse(token);
            request.setAttribute(AUTHENTICATED_USER_ATTR, user);
            return true;
        } catch (InvalidJwtException e) {
            return unauthorized(response, e.getMessage());
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean unauthorized(HttpServletResponse response, String detail) throws IOException {
        log.debug("Rejecting request with 401: {}", detail);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Map.of("error", "Unauthorized"));
        return false;
    }
}
