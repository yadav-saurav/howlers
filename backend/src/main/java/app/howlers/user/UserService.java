package app.howlers.user;

import app.howlers.common.security.AccountAccessRevoked;
import org.springframework.context.ApplicationEventPublisher;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.MembershipDao;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.notification.NotificationService;
import app.howlers.storage.MediaService;
import app.howlers.user.dto.UserDtos.Me;
import app.howlers.user.dto.UserDtos.PublicProfile;
import app.howlers.user.dto.UserDtos.UpdateProfileRequest;
import app.howlers.user.dto.UserDtos.UpdateSettingsRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserService {
    private final UserRepository users;
    private final MembershipDao memberships;
    private final CommunityRepository communities;
    private final MediaService media;
    private final PasswordEncoder encoder;
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final app.howlers.messaging.DmAccess dmAccess;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository users, MembershipDao memberships, CommunityRepository communities, MediaService media,
                       PasswordEncoder encoder, JdbcClient jdbc, NotificationService notifications,
                       app.howlers.messaging.DmAccess dmAccess, ApplicationEventPublisher events) {
        this.events = events;
        this.dmAccess = dmAccess;
        this.users = users;
        this.memberships = memberships;
        this.communities = communities;
        this.media = media;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    public User requireActive(long id) {
        return users.findById(id).filter(User::isActive).orElseThrow(() -> ApiException.unauthorized("Account unavailable"));
    }

    @Transactional(readOnly = true)
    public Me me(AuthUser user) {
        return UserMapper.toMe(requireActive(user.id()));
    }

    @Transactional
    public Me updateProfile(AuthUser user, UpdateProfileRequest req) {
        User u = requireActive(user.id());
        if (req.displayName() != null) u.setDisplayName(req.displayName().isBlank() ? u.getUsername() : req.displayName().strip());
        if (req.bio() != null) u.setBio(req.bio().strip());
        return UserMapper.toMe(u);
    }

    @Transactional
    public Me updateSettings(AuthUser user, UpdateSettingsRequest req) {
        User u = requireActive(user.id());
        if (req.theme() != null) u.setTheme(req.theme());
        if (req.showNsfw() != null) u.setShowNsfw(req.showNsfw());
        if (req.profilePublic() != null) u.setProfilePublic(req.profilePublic());
        if (req.showCommunities() != null) u.setShowCommunities(req.showCommunities());
        if (req.emailNotifications() != null) u.setEmailNotifications(req.emailNotifications());
        if (req.dmPolicy() != null) u.setDmPolicy(req.dmPolicy());
        return UserMapper.toMe(u);
    }

    @Transactional
    public Me uploadImage(AuthUser user, MultipartFile file, boolean banner) {
        User u = requireActive(user.id());
        var img = media.storeImage(file, banner ? "banners" : "avatars", banner ? 8L * 1024 * 1024 : 5L * 1024 * 1024);
        if (banner) u.setBannerUrl(img.url()); else u.setAvatarUrl(img.url());
        return UserMapper.toMe(u);
    }

    @Transactional
    public void changePassword(AuthUser user, String current, String next) {
        User u = requireActive(user.id());
        if (!encoder.matches(current, u.getPasswordHash())) throw ApiException.field("currentPassword", "Current password is incorrect");
        u.setPasswordHash(encoder.encode(next));
    }

    @Transactional
    public void deactivate(AuthUser user, String password) {
        User u = requireActive(user.id());
        if (!encoder.matches(password, u.getPasswordHash())) throw ApiException.field("password", "Password is incorrect");
        u.setStatus(User.Status.DEACTIVATED);
        revokeSessions(u.getId());
        events.publishEvent(new AccountAccessRevoked(u.getId(), "deactivated"));
    }

    /**
     * Account deletion anonymises the row instead of removing it, so threads keep their shape; all content
     * then renders with a null author. Personal data (email, bio, images, password) is wiped.
     */
    @Transactional
    public void delete(AuthUser user, String password) {
        User u = requireActive(user.id());
        if (!encoder.matches(password, u.getPasswordHash())) throw ApiException.field("password", "Password is incorrect");
        String tomb = "deleted_" + u.getId();
        u.setUsername(tomb.length() > 21 ? tomb.substring(0, 21) : tomb);
        u.setEmail(tomb + "@deleted.invalid");
        u.setDisplayName(null);
        u.setBio(null);
        u.setAvatarUrl(null);
        u.setBannerUrl(null);
        u.setPasswordHash("!");
        u.setStatus(User.Status.DELETED);
        u.setDeletedAt(Instant.now());
        revokeSessions(u.getId());
        events.publishEvent(new AccountAccessRevoked(u.getId(), "deleted"));
        jdbc.sql("DELETE FROM user_follows WHERE follower_id = ? OR followee_id = ?").params(u.getId(), u.getId()).update();
        jdbc.sql("DELETE FROM community_members WHERE user_id = ? AND role <> 'OWNER'").param(u.getId()).update();
    }

    private void revokeSessions(long userId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(userId).update();
    }

    @Transactional(readOnly = true)
    public PublicProfile profile(String username, AuthUser viewer) {
        User u = users.findByUsernameIgnoreCase(username).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (User.Status.SUSPENDED.name().equals(u.getStatus())) throw ApiException.notFound("User");
        boolean self = viewer != null && viewer.id() == u.getId();
        boolean following = viewer != null && !self && jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM user_follows WHERE follower_id = ? AND followee_id = ?)")
                .params(viewer.id(), u.getId()).query(Boolean.class).single();
        boolean isPrivate = !u.isProfilePublic() && !self;
        boolean blocked = viewer != null && !self && jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?)")
                .params(viewer.id(), u.getId()).query(Boolean.class).single();
        List<CommunityRef> joined = List.of();
        if (!isPrivate && (u.isShowCommunities() || self)) {
            joined = communities.findByIdIn(memberships.joinedCommunityIds(u.getId())).stream()
                    .filter(c -> c.visibility() != app.howlers.community.Community.Visibility.PRIVATE || self)
                    .map(CommunityMapper::ref).limit(50).toList();
        }
        List<CommunityRef> moderated = communities.findByIdIn(memberships.moderatedCommunityIds(u.getId())).stream()
                .filter(c -> c.visibility() != app.howlers.community.Community.Visibility.PRIVATE || self)
                .map(CommunityMapper::ref).toList();
        return new PublicProfile(u.getId(), u.getUsername(), u.getDisplayName(), u.getAvatarUrl(), u.getBannerUrl(),
                isPrivate ? null : u.getBio(), u.getCreatedAt(), u.getPostKarma(), u.getCommentKarma(), u.getFollowerCount(),
                u.getFollowingCount(), following, self, isPrivate, blocked,
                viewer != null && !self && dmAccess.cannotMessageReason(viewer.id(), u.getId()) == null, joined, moderated);
    }

    public User requireByUsername(String username) {
        return users.findByUsernameIgnoreCase(username).filter(User::isActive).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Profile privacy: a private profile hides its activity lists from everyone but the owner. */
    public void requireActivityVisible(User u, AuthUser viewer) {
        boolean self = viewer != null && viewer.id() == u.getId();
        if (!u.isProfilePublic() && !self) throw ApiException.forbidden("This profile is private");
    }

    @Transactional
    public void follow(AuthUser user, String username) {
        User target = requireByUsername(username);
        if (target.getId() == user.id()) throw ApiException.badRequest("You can't follow yourself");
        int n = jdbc.sql("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(user.id(), target.getId()).update();
        if (n > 0) {
            jdbc.sql("UPDATE users SET follower_count = follower_count + 1 WHERE id = ?").param(target.getId()).update();
            jdbc.sql("UPDATE users SET following_count = following_count + 1 WHERE id = ?").param(user.id()).update();
            notifications.newFollower(target.getId(), user.id(), user.username());
        }
    }

    @Transactional
    public void unfollow(AuthUser user, String username) {
        User target = requireByUsername(username);
        int n = jdbc.sql("DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?").params(user.id(), target.getId()).update();
        if (n > 0) {
            jdbc.sql("UPDATE users SET follower_count = GREATEST(follower_count - 1, 0) WHERE id = ?").param(target.getId()).update();
            jdbc.sql("UPDATE users SET following_count = GREATEST(following_count - 1, 0) WHERE id = ?").param(user.id()).update();
        }
    }
}
