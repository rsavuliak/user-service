package com.savuliak.userservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers base class using the singleton pattern. The container
 * is started once per JVM and left running until the JVM exits (Ryuk cleans
 * it up). This keeps it alive across multiple IT classes — using
 * {@code @Testcontainers} + {@code @Container} would stop the container at
 * the end of each class, breaking subsequent tests in the same build.
 *
 * Abstract so Surefire/Failsafe never try to instantiate it directly as a
 * test class.
 */
public abstract class PostgresContainerBase {

    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("user_service")
                .withUsername("postgres")
                .withPassword("postgres")
                .withReuse(false);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
