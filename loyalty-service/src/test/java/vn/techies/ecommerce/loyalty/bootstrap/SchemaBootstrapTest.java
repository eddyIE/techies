package vn.techies.ecommerce.loyalty.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.loyalty.AbstractPostgresTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container the service runs against was initialised before `loyalty` existed, so the schema
 * has to arrive with Flyway rather than with `docker/postgres/init.sql`.
 */
@SpringBootTest
class SchemaBootstrapTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway creates the loyalty schema itself, without init.sql")
    void createsItsOwnSchema() {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'loyalty'",
                Integer.class);

        assertThat(found).isEqualTo(1);
    }
}
