package vn.techies.ecommerce.identity.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import vn.techies.ecommerce.identity.AbstractPostgresTest;
import vn.techies.ecommerce.identity.domain.Address;
import vn.techies.ecommerce.identity.domain.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AddressRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private AddressRepository addresses;
    @Autowired
    private UserRepository users;

    private UUID newUser(String email) {
        return users.saveAndFlush(User.create(email, "hash", "Name", "0900000000")).getId();
    }

    private Address addr(UUID userId, boolean isDefault) {
        return Address.create(userId, "Recipient", "0901234567", "1 Le Loi",
                "Ben Nghe", "Quan 1", "Ho Chi Minh", isDefault);
    }

    @Test
    @DisplayName("lists a user's addresses default-first")
    void listsDefaultFirst() {
        UUID userId = newUser("list@example.com");
        addresses.saveAndFlush(addr(userId, false));
        addresses.saveAndFlush(addr(userId, true));

        var found = addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).isDefault()).isTrue();
    }

    @Test
    @DisplayName("the partial unique index allows many non-default addresses")
    void allowsManyNonDefaults() {
        UUID userId = newUser("many@example.com");
        addresses.saveAndFlush(addr(userId, false));
        addresses.saveAndFlush(addr(userId, false));
        addresses.saveAndFlush(addr(userId, false));

        assertThat(addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId)).hasSize(3);
    }

    @Test
    @DisplayName("but rejects a second default address for the same user")
    void rejectsSecondDefault() {
        UUID userId = newUser("default@example.com");
        addresses.saveAndFlush(addr(userId, true));

        assertThatThrownBy(() -> addresses.saveAndFlush(addr(userId, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two different users may each have their own default")
    void defaultsAreScopedPerUser() {
        UUID first = newUser("u1@example.com");
        UUID second = newUser("u2@example.com");

        addresses.saveAndFlush(addr(first, true));
        addresses.saveAndFlush(addr(second, true));

        assertThat(addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(first)).hasSize(1);
        assertThat(addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(second)).hasSize(1);
    }

    @Test
    @DisplayName("scopes lookup by owner so one user cannot fetch another's address")
    void scopesByOwner() {
        UUID owner = newUser("owner@example.com");
        UUID other = newUser("other@example.com");
        Address saved = addresses.saveAndFlush(addr(owner, false));

        assertThat(addresses.findByIdAndUserId(saved.getId(), owner)).isPresent();
        assertThat(addresses.findByIdAndUserId(saved.getId(), other)).isEmpty();
    }
}
