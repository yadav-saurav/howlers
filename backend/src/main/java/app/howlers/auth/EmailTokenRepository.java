package app.howlers.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {
    Optional<EmailToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update EmailToken t set t.usedAt = CURRENT_TIMESTAMP where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    void invalidateOutstanding(@Param("userId") long userId, @Param("purpose") String purpose);
}
