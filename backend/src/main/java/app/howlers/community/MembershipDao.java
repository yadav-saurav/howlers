package app.howlers.community;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Membership is a pure join table with a role; plain SQL keeps it simple and index-friendly. */
@Repository
public class MembershipDao {
    private final JdbcClient jdbc;

    public MembershipDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MemberRow(long userId, String role, Instant joinedAt) {}

    public Optional<CommunityRole> role(long communityId, long userId) {
        return jdbc.sql("SELECT role FROM community_members WHERE community_id = ? AND user_id = ?")
                .params(communityId, userId).query(String.class).optional().map(CommunityRole::valueOf);
    }

    /** @return true if a new membership row was created (counter is bumped in the same statement batch). */
    public boolean join(long communityId, long userId, CommunityRole role) {
        int n = jdbc.sql("INSERT INTO community_members (community_id, user_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(communityId, userId, role.name()).update();
        if (n > 0) {
            jdbc.sql("UPDATE communities SET member_count = member_count + 1 WHERE id = ?").param(communityId).update();
        }
        return n > 0;
    }

    public boolean leave(long communityId, long userId) {
        int n = jdbc.sql("DELETE FROM community_members WHERE community_id = ? AND user_id = ?")
                .params(communityId, userId).update();
        if (n > 0) {
            jdbc.sql("UPDATE communities SET member_count = GREATEST(member_count - 1, 0) WHERE id = ?").param(communityId).update();
        }
        return n > 0;
    }

    public void setRole(long communityId, long userId, CommunityRole role) {
        jdbc.sql("UPDATE community_members SET role = ? WHERE community_id = ? AND user_id = ?")
                .params(role.name(), communityId, userId).update();
    }

    public List<MemberRow> members(long communityId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT user_id, role, joined_at FROM community_members WHERE community_id = ?
                        ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'MODERATOR' THEN 2
                                 WHEN 'CONTRIBUTOR' THEN 3 ELSE 4 END, joined_at
                        LIMIT ? OFFSET ?""")
                .params(communityId, limit, offset)
                .query((rs, i) -> new MemberRow(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()))
                .list();
    }

    public long memberCount(long communityId) {
        return jdbc.sql("SELECT count(*) FROM community_members WHERE community_id = ?").param(communityId)
                .query(Long.class).single();
    }

    public List<MemberRow> moderators(long communityId) {
        return jdbc.sql("""
                        SELECT user_id, role, joined_at FROM community_members
                        WHERE community_id = ? AND role IN ('OWNER','ADMIN','MODERATOR')
                        ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, joined_at""")
                .param(communityId)
                .query((rs, i) -> new MemberRow(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Which of {@code communityIds} the user belongs to (batch, avoids N+1 on lists). */
    public Set<Long> memberOf(long userId, Collection<Long> communityIds) {
        if (communityIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT community_id FROM community_members WHERE user_id = :u AND community_id IN (:ids)")
                .param("u", userId).param("ids", communityIds).query(Long.class).list());
    }

    public List<Long> joinedCommunityIds(long userId) {
        return jdbc.sql("SELECT community_id FROM community_members WHERE user_id = ? ORDER BY joined_at")
                .param(userId).query(Long.class).list();
    }

    public List<Long> moderatedCommunityIds(long userId) {
        return jdbc.sql("SELECT community_id FROM community_members WHERE user_id = ? AND role IN ('OWNER','ADMIN','MODERATOR')")
                .param(userId).query(Long.class).list();
    }
}
