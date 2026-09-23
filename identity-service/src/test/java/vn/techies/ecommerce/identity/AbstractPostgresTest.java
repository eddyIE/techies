package vn.techies.ecommerce.identity;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One Postgres container shared by every test class in this module. Declared static so
 * Testcontainers reuses it across classes instead of paying container startup per class.
 */
@Testcontainers
public abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("techies")
            .withUsername("techies")
            .withPassword("techies_local_dev");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void flywayProperties(DynamicPropertyRegistry registry) {
        // Migrations own the schema; Hibernate only validates that entities match it.
        registry.add("spring.datasource.hikari.schema", () -> "identity");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "identity");
        registry.add("spring.flyway.default-schema", () -> "identity");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "identity");
    }
}
