package app.howlers.mute;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.Community;
import app.howlers.community.CommunityRepository;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.User;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mutes are private and one-directional, and lighter than blocks (D-021): a muted person's posts leave your feeds,
 * their comments arrive collapsed and they can't notify you, but they can still see and reply to you. A muted
 * community leaves your Home and Popular/All feeds; its own page still works.
 */
@Service
public class MuteService {
    private final JdbcClient jdbc;
    private final UserRepository users;
    private final UserDirectory directory;
    private final CommunityRepository communities;

    public MuteService(JdbcClient jdbc, UserRepository users, UserDirectory directory, CommunityRepository communities) {
        this.jdbc = jdbc;
        this.users = users;
        this.directory = directory;
        this.communities = communities;
    }

    public record Mutes(List<UserSummary> users, List<CommunityRef> communities) {}

    @Transactional
    public void muteUser(AuthUser user, String username, boolean mute) {
        User target = users.findByUsernameIgnoreCase(username).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (target.getId() == user.id()) throw ApiException.badRequest("You can't mute yourself");
        if (mute) {
            jdbc.sql("INSERT INTO user_mutes (muter_id, muted_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), target.getId()).update();
        } else {
            jdbc.sql("DELETE FROM user_mutes WHERE muter_id = ? AND muted_id = ?").params(user.id(), target.getId()).update();
        }
    }

    @Transactional
    public void muteCommunity(AuthUser user, String slug, boolean mute) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        if (mute) {
            jdbc.sql("INSERT INTO community_mutes (user_id, community_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), c.getId()).update();
        } else {
            jdbc.sql("DELETE FROM community_mutes WHERE user_id = ? AND community_id = ?").params(user.id(), c.getId()).update();
        }
    }

    @Transactional(readOnly = true)
    public Mutes list(AuthUser user) {
        List<Long> ids = jdbc.sql("SELECT muted_id FROM user_mutes WHERE muter_id = ? ORDER BY created_at DESC").param(user.id())
                .query(Long.class).list();
        Map<Long, UserSummary> u = directory.summaries(ids);
        List<CommunityRef> cs = jdbc.sql("""
                        SELECT c.id, c.slug, c.name, c.icon_url FROM community_mutes m JOIN communities c ON c.id = m.community_id
                        WHERE m.user_id = ? ORDER BY m.created_at DESC""")
                .param(user.id())
                .query((rs, i) -> new CommunityRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        return new Mutes(ids.stream().map(u::get).filter(Objects::nonNull).toList(), cs);
    }
}
