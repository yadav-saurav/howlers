package app.howlers.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Row lock so two concurrent refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") long userId, @Param("now") Instant now);

    /** "Sign out everywhere else": every session of the user except the given family. */
    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.familyId <> :keep
              and t.revokedAt is null""")
    int revokeAllExceptFamily(@Param("userId") long userId, @Param("keep") UUID keep, @Param("now") Instant now);

    /** Active sessions = the live (un-rotated, un-revoked, unexpired) head of each family. */
    @Query("""
            select t from RefreshToken t where t.userId = :userId and t.revokedAt is null
              and t.rotatedAt is null and t.expiresAt > :now order by t.lastUsedAt desc""")
    List<RefreshToken> findActiveSessions(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
