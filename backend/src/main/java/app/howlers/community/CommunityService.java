package app.howlers.community;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.OffsetPage;
import app.howlers.community.dto.CommunityDtos.CommunityDetail;
import app.howlers.community.dto.CommunityDtos.CommunitySummary;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.community.dto.CommunityDtos.FlairDto;
import app.howlers.community.dto.CommunityDtos.FlairInput;
import app.howlers.community.dto.CommunityDtos.MemberDto;
import app.howlers.community.dto.CommunityDtos.ModeratorDto;
import app.howlers.community.dto.CommunityDtos.RuleDto;
import app.howlers.community.dto.CommunityDtos.RuleInput;
import app.howlers.community.dto.CommunityDtos.UpdateCommunityRequest;
import app.howlers.community.dto.CommunityDtos.ViewerDto;
import app.howlers.notification.NotificationService;
import app.howlers.storage.MediaService;
import app.howlers.topic.Topic;
import app.howlers.topic.TopicRepository;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CommunityService {
    private static final Set<String> RESERVED = Set.of("all", "popular", "home", "new", "settings", "submit", "admin",
            "mod", "howlers", "explore", "search");

    private final CommunityRepository communities;
    private final MembershipDao memberships;
    private final CommunityAccess access;
    private final TopicRepository topics;
    private final UserDirectory userDirectory;
    private final UserRepository users;
    private final MediaService media;
    private final NotificationService notifications;

    public CommunityService(CommunityRepository communities, MembershipDao memberships, CommunityAccess access,
                            TopicRepository topics, UserDirectory userDirectory, UserRepository users,
                            MediaService media, NotificationService notifications) {
        this.communities = communities;
        this.memberships = memberships;
        this.access = access;
        this.topics = topics;
        this.userDirectory = userDirectory;
        this.users = users;
        this.media = media;
        this.notifications = notifications;
    }

    public Community require(String slug) {
        return communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
    }

    /** @return the new slug; callers load the detail in a fresh transaction so counters are current. */
    @Transactional
    public String create(AuthUser user, CreateCommunityRequest req) {
        String slug = req.slug().toLowerCase();
        if (RESERVED.contains(slug)) throw ApiException.conflict("slug", "That name is reserved");
        if (communities.existsBySlug(slug)) throw ApiException.conflict("slug", "A community with that name already exists");
        Topic topic = req.topicId() == null ? null
                : topics.findById(req.topicId()).orElseThrow(() -> ApiException.field("topicId", "Unknown topic"));
        Community c = new Community(slug, req.name().trim(), req.description(), topic,
                Community.Visibility.valueOf(req.visibility()), normaliseTags(req.tags()), user.id());
        List<RuleInput> rules = req.rules() == null ? List.of() : req.rules();
        for (int i = 0; i < rules.size(); i++) {
            c.getRules().add(new CommunityRule(c, i, rules.get(i).title().trim(), rules.get(i).description()));
        }
        try {
            communities.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("slug", "A community with that name already exists");
        }
        memberships.join(c.getId(), user.id(), CommunityRole.OWNER);
        return c.getSlug();
    }

    @Transactional(readOnly = true)
    public CommunityDetail detail(String slug, AuthUser user) {
        Community c = require(slug);
        Optional<CommunityRole> role = access.roleOf(c, user);
        boolean canView = access.canView(c, role, user);
        Optional<CommunityAccess.Ban> ban = access.banOf(c.getId(), user);
        ViewerDto viewer = new ViewerDto(role.isPresent(), role.map(Enum::name).orElse(null),
                access.canPost(c, role, user), access.canModerate(role, user), canView,
                ban.isPresent(), ban.flatMap(CommunityAccess.Ban::until).orElse(null));
        List<MembershipDao.MemberRow> mods = memberships.moderators(c.getId());
        Map<Long, UserSummary> modUsers = userDirectory.summaries(mods.stream().map(MembershipDao.MemberRow::userId).toList());
        List<ModeratorDto> moderators = mods.stream().filter(m -> modUsers.containsKey(m.userId()))
                .map(m -> new ModeratorDto(modUsers.get(m.userId()), m.role())).toList();
        List<RuleDto> rules = c.getRules().stream()
                .map(r -> new RuleDto(r.getId(), r.getTitle(), r.getDescription(), r.getPosition())).toList();
        List<FlairDto> flairs = c.getFlairs().stream().map(CommunityMapper::flair).toList();
        return new CommunityDetail(c.getId(), c.getSlug(), c.getName(), c.getDescription(), c.getIconUrl(),
                c.getBannerUrl(), Topic.TopicDto.of(c.getTopic()), c.visibility().name(), c.getMemberCount(),
                c.getTags(), role.isPresent(), c.getCreatedAt(), rules, flairs, moderators, viewer, c.getStatus(),
                c.getStatusReason());
    }

    @Transactional
    public CommunityDetail update(String slug, AuthUser user, UpdateCommunityRequest req) {
        Community c = require(slug);
        access.requireModerator(c, user);
        if (req.name() != null) c.setName(req.name().trim());
        if (req.description() != null) c.setDescription(req.description());
        if (req.topicId() != null) {
            c.setTopic(topics.findById(req.topicId()).orElseThrow(() -> ApiException.field("topicId", "Unknown topic")));
        }
        if (req.visibility() != null) c.setVisibility(Community.Visibility.valueOf(req.visibility()));
        if (req.tags() != null) c.setTags(normaliseTags(req.tags()));
        return detail(slug, user);
    }

    @Transactional
    public CommunityDetail replaceRules(String slug, AuthUser user, List<RuleInput> rules) {
        Community c = require(slug);
        access.requireModerator(c, user);
        if (rules.size() > 15) throw ApiException.badRequest("At most 15 rules");
        c.getRules().clear();
        communities.flush();
        for (int i = 0; i < rules.size(); i++) {
            c.getRules().add(new CommunityRule(c, i, rules.get(i).title().trim(), rules.get(i).description()));
        }
        return detail(slug, user);
    }

    /** Upsert-by-id; flairs missing from the list are deleted (posts keep working via ON DELETE SET NULL). */
    @Transactional
    public CommunityDetail replaceFlairs(String slug, AuthUser user, List<FlairInput> input) {
        Community c = require(slug);
        access.requireModerator(c, user);
        if (input.size() > 30) throw ApiException.badRequest("At most 30 flairs");
        Map<Long, CommunityFlair> existing = c.getFlairs().stream()
                .collect(Collectors.toMap(CommunityFlair::getId, Function.identity()));
        List<CommunityFlair> next = new ArrayList<>();
        for (FlairInput f : input) {
            CommunityFlair flair = f.id() != null ? existing.get(f.id()) : null;
            if (flair == null) {
                flair = new CommunityFlair(c, f.text().trim(), f.color().toUpperCase());
            } else {
                flair.setText(f.text().trim());
                flair.setColor(f.color().toUpperCase());
            }
            next.add(flair);
        }
        c.getFlairs().retainAll(next);
        for (CommunityFlair f : next) if (!c.getFlairs().contains(f)) c.getFlairs().add(f);
        return detail(slug, user);
    }

    @Transactional
    public CommunityDetail uploadImage(String slug, AuthUser user, MultipartFile file, boolean banner) {
        Community c = require(slug);
        access.requireModerator(c, user);
        var img = media.storeImage(file, banner ? "banners" : "icons", banner ? 8L * 1024 * 1024 : 3L * 1024 * 1024);
        if (banner) c.setBannerUrl(img.url()); else c.setIconUrl(img.url());
        return detail(slug, user);
    }

    @Transactional
    public void join(String slug, AuthUser user) {
        Community c = require(slug);
        if (c.visibility() == Community.Visibility.PRIVATE && !user.isAdmin()) {
            throw ApiException.forbidden("Private communities are invite-only");
        }
        access.requireNotBanned(c.getId(), user);
        memberships.join(c.getId(), user.id(), CommunityRole.MEMBER);
    }

    @Transactional
    public void leave(String slug, AuthUser user) {
        Community c = require(slug);
        Optional<CommunityRole> role = memberships.role(c.getId(), user.id());
        if (role.isEmpty()) return;
        if (role.get() == CommunityRole.OWNER) {
            throw ApiException.badRequest("Owners must transfer ownership before leaving");
        }
        memberships.leave(c.getId(), user.id());
    }

    @Transactional(readOnly = true)
    public OffsetPage<MemberDto> members(String slug, AuthUser user, int page, int size) {
        Community c = require(slug);
        access.requireView(c, user);
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        List<MembershipDao.MemberRow> rows = memberships.members(c.getId(), s, p * s);
        Map<Long, UserSummary> u = userDirectory.summaries(rows.stream().map(MembershipDao.MemberRow::userId).toList());
        List<MemberDto> items = rows.stream().filter(r -> u.containsKey(r.userId()))
                .map(r -> new MemberDto(u.get(r.userId()), r.role(), r.joinedAt())).toList();
        return OffsetPage.of(items, p, s, memberships.memberCount(c.getId()));
    }

    /**
     * Role changes: only OWNER/ADMIN may change roles; nobody may change the owner's role or grant a role
     * at or above their own (admins cannot mint admins, only the owner can).
     */
    @Transactional
    public void setRole(String slug, AuthUser actor, String username, CommunityRole newRole) {
        Community c = require(slug);
        CommunityRole actorRole = access.requireModerator(c, actor);
        if (!actorRole.atLeast(CommunityRole.ADMIN)) throw ApiException.forbidden("Only owners and admins can change roles");
        var target = users.findByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("User"));
        CommunityRole current = memberships.role(c.getId(), target.getId())
                .orElseThrow(() -> ApiException.badRequest("That user is not a member"));
        if (current == CommunityRole.OWNER) throw ApiException.forbidden("The owner's role cannot be changed");
        if (actorRole != CommunityRole.OWNER && (newRole.atLeast(CommunityRole.ADMIN) || current.atLeast(CommunityRole.ADMIN))) {
            throw ApiException.forbidden("Only the owner can manage admins");
        }
        memberships.setRole(c.getId(), target.getId(), newRole);
        if (newRole.isModerator() && !current.isModerator()) {
            notifications.communityRole(target.getId(), actor.id(), c.getSlug(), newRole.name());
        }
    }

    @Transactional(readOnly = true)
    public OffsetPage<CommunitySummary> directory(String q, String topic, String sort, int page, int size, AuthUser user) {
        Sort s = "new".equals(sort) ? Sort.by(Sort.Direction.DESC, "createdAt", "id")
                : Sort.by(Sort.Direction.DESC, "memberCount").and(Sort.by("id"));
        var result = communities.directory(q == null ? "" : q.trim().toLowerCase(), topic == null ? "" : topic,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), s));
        Set<Long> mine = user == null ? Set.of()
                : memberships.memberOf(user.id(), result.getContent().stream().map(Community::getId).toList());
        return OffsetPage.of(result, c -> CommunityMapper.summary(c, mine.contains(c.getId())));
    }

    @Transactional(readOnly = true)
    public List<CommunitySummary> byIds(List<Long> ids, AuthUser user) {
        Map<Long, Community> byId = new HashMap<>();
        communities.findByIdIn(ids).forEach(c -> byId.put(c.getId(), c));
        Set<Long> mine = user == null ? Set.of() : memberships.memberOf(user.id(), byId.keySet());
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(c -> CommunityMapper.summary(c, mine.contains(c.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<CommunitySummary> trending(int limit, AuthUser user) {
        return byIds(communities.trendingIds(Math.min(Math.max(limit, 1), 20)), user);
    }

    @Transactional(readOnly = true)
    public List<CommunitySummary> mine(AuthUser user) {
        List<CommunitySummary> list = new ArrayList<>(byIds(memberships.joinedCommunityIds(user.id()), user));
        list.sort(Comparator.comparing(CommunitySummary::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private static List<String> normaliseTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream().map(String::trim).map(String::toLowerCase).filter(t -> !t.isEmpty()).distinct().limit(8).toList();
    }
}
