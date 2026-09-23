package vn.techies.ecommerce.identity.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import vn.techies.ecommerce.identity.AbstractPostgresTest;
import vn.techies.ecommerce.identity.domain.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("V1 migration applies and a user round-trips")
    void persistsUser() {
        User saved = users.saveAndFlush(User.create("Bob@Example.com", "hash", "Bob", "0901234567"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).as("stored lowercased").isEqualTo("bob@example.com");
        assertThat(users.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("email lookup ignores case")
    void findsByEmailIgnoringCase() {
        users.saveAndFlush(User.create("alice@example.com", "hash", "Alice", "0900000001"));

        assertThat(users.findByEmailIgnoreCase("ALICE@EXAMPLE.COM")).isPresent();
        assertThat(users.existsByEmailIgnoreCase("Alice@Example.Com")).isTrue();
        assertThat(users.existsByEmailIgnoreCase("nobody@example.com")).isFalse();
    }

    @Test
    @DisplayName("the unique index rejects a duplicate email differing only in case")
    void rejectsDuplicateEmailDifferingByCase() {
        users.saveAndFlush(User.create("dup@example.com", "hash", "First", "0900000002"));

        assertThatThrownBy(() -> users.saveAndFlush(User.create("DUP@EXAMPLE.COM", "hash", "Second", "0900000003")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
