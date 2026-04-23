package com.savuliak.userservice.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.savuliak.userservice.config.InternalApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Api-Key";

    private final byte[] expectedKey;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(InternalApiProperties properties, ObjectMapper objectMapper) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        byte[] provided = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(provided, expectedKey)) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Map.of("error", "Unauthorized"));
    }
}
