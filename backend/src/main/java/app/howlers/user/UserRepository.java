package app.howlers.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    List<User> findByIdIn(Collection<Long> ids);

    @Modifying
    @Query(value = "UPDATE users SET post_karma = post_karma + :delta WHERE id = :id", nativeQuery = true)
    void addPostKarma(@Param("id") long id, @Param("delta") int delta);

    @Modifying
    @Query(value = "UPDATE users SET comment_karma = comment_karma + :delta WHERE id = :id", nativeQuery = true)
    void addCommentKarma(@Param("id") long id, @Param("delta") int delta);

    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND NOT EXISTS (SELECT 1 FROM user_follows f WHERE f.follower_id = :viewerId AND f.followee_id = u.id)
            ORDER BY (u.post_karma + u.comment_karma) DESC, u.id
            LIMIT :limit""", nativeQuery = true)
    List<User> suggestedToFollow(@Param("viewerId") long viewerId, @Param("limit") int limit);
}
