package vn.techies.ecommerce.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.techies.ecommerce.identity.domain.UserAvatar;

import java.util.Set;
import java.util.UUID;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {

    /**
     * Which of these users have an avatar, without loading any image bytes.
     * Used to populate `avatarUrl` on a user response.
     */
    @Query("SELECT a.userId FROM UserAvatar a WHERE a.userId IN :ids")
    Set<UUID> findUserIdsWithAvatar(Iterable<UUID> ids);

    boolean existsByUserId(UUID userId);
}
