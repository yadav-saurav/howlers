package app.howlers.podcast;

import app.howlers.antivirus.Antivirus;
import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.insight.TextSignals;
import app.howlers.podcast.PodcastDtos.Chapter;
import app.howlers.podcast.PodcastDtos.ChapterInput;
import app.howlers.podcast.PodcastDtos.CreateEpisode;
import app.howlers.podcast.PodcastDtos.EpisodeDetail;
import app.howlers.podcast.PodcastDtos.EpisodeSummary;
import app.howlers.podcast.PodcastDtos.EpisodeViewer;
import app.howlers.podcast.PodcastDtos.Home;
import app.howlers.podcast.PodcastDtos.Progress;
import app.howlers.podcast.PodcastDtos.Segment;
import app.howlers.podcast.PodcastDtos.SourceInput;
import app.howlers.podcast.PodcastDtos.SourceRef;
import app.howlers.podcast.PodcastDtos.UpdateEpisode;
import app.howlers.post.PostService;
import app.howlers.storage.MediaService;
import app.howlers.storage.StorageService;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Community podcasts: discovery, playback state and the studio workflow (DRAFT → IN_REVIEW → PUBLISHED / ARCHIVED).
 * Episodes are managed by the community's moderators. Publishing requires real uploaded media and a timed transcript.
 */
@Service
public class PodcastService {
    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final StorageService storage;
    private final MediaService media;
    private final UserDirectory users;
    private final PostService posts;
    private final RateLimiter limiter;
    private final long maxMediaBytes;
    private final Antivirus antivirus;

    public PodcastService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, StorageService storage,
                          MediaService media, UserDirectory users, PostService posts, RateLimiter limiter, AppProperties props,
                          Antivirus antivirus) {
        this.antivirus = antivirus;
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.storage = storage;
        this.media = media;
        this.users = users;
        this.posts = posts;
        this.limiter = limiter;
        this.maxMediaBytes = props.storage().maxMediaMb() * 1024 * 1024;
    }

    public long maxUploadMb() {
        return maxMediaBytes / 1024 / 1024;
    }

    // ------------------------------------------------------------------ row model

    record Row(long id, long communityId, Long createdBy, Long publishedBy, String title, String summary, String type, String status,
               String mediaKind, String mediaKey, String mediaContentType, Integer duration, String thumbnailUrl, boolean allowDownload,
               String script, String scriptSource, String scriptStatus, String scriptError, int playCount, Instant publishedAt,
               Instant createdAt, String audioOrigin, String audioProvider, String audioModel) {
        /** Null when there's no media yet; otherwise UPLOADED or AI_TTS. */
        String origin() {
            return mediaKey == null ? null : audioOrigin;
        }
    }

    private static final String COLS = """
            e.id, e.community_id, e.created_by, e.published_by, e.title, e.summary, e.type, e.status, e.media_kind, e.media_key,
            e.media_content_type, e.duration_seconds, e.thumbnail_url, e.allow_download, e.script, e.script_source, e.script_status,
            e.script_error, e.play_count, e.published_at, e.created_at, e.audio_origin, e.audio_provider, e.audio_model""";

    private Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("community_id"), (Long) rs.getObject("created_by"), (Long) rs.getObject("published_by"),
                rs.getString("title"), rs.getString("summary"), rs.getString("type"), rs.getString("status"), rs.getString("media_kind"),
                rs.getString("media_key"), rs.getString("media_content_type"), (Integer) rs.getObject("duration_seconds"),
                rs.getString("thumbnail_url"), rs.getBoolean("allow_download"), rs.getString("script"), rs.getString("script_source"),
                rs.getString("script_status"), rs.getString("script_error"), rs.getInt("play_count"), ts(rs, "published_at"),
                ts(rs, "created_at"), rs.getString("audio_origin"), rs.getString("audio_provider"), rs.getString("audio_model"));
    }

    Row load(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM podcast_episodes e WHERE e.id = ?").param(id).query(this::row).optional()
                .orElseThrow(() -> ApiException.notFound("Episode"));
    }

    /** Published episodes are visible to whoever can see the community; anything else only to its managers. */
    private Community requireVisible(Row r, AuthUser user) {
        Community c = communities.findById(r.communityId()).orElseThrow(() -> ApiException.notFound("Episode"));
        access.requireView(c, user);
        if (!"PUBLISHED".equals(r.status()) && !access.canModerate(access.roleOf(c, user), user)) {
            throw ApiException.notFound("Episode");
        }
        return c;
    }

    Community requireManager(Row r, AuthUser user) {
        Community c = communities.findById(r.communityId()).orElseThrow(() -> ApiException.notFound("Episode"));
        access.requireModerator(c, user);
        return c;
    }

    // ------------------------------------------------------------------ discovery

    private static final String VISIBLE = """
            e.status = 'PUBLISHED' AND (c.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members vm
                 WHERE vm.community_id = c.id AND vm.user_id = :viewer))""";

    @Transactional(readOnly = true)
    public CursorPage<EpisodeSummary> list(String type, String communitySlug, String sort, String cursor, int limit, AuthUser user) {
        boolean popular = "popular".equals(sort);
        int offset = popular ? Cursors.decodeOffset(cursor) : 0;
        Cursors.Key key = popular ? null : Cursors.decode(cursor);
        List<Row> rows = jdbc.sql("SELECT " + COLS + " FROM podcast_episodes e JOIN communities c ON c.id = e.community_id WHERE "
                        + VISIBLE + """

                        AND (:type = '' OR e.type = :type) AND (:slug = '' OR lower(c.slug) = lower(:slug))
                        AND (:hasCursor = FALSE OR e.id < :cid)
                        ORDER BY """ + (popular ? " e.play_count DESC, e.id DESC OFFSET :off" : " e.id DESC") + " LIMIT :lim")
                .param("viewer", viewer(user)).param("type", type == null ? "" : type).param("slug", communitySlug == null ? "" : communitySlug)
                .param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("off", offset).param("lim", limit + 1)
                .query(this::row).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        String next = !more ? null : popular ? Cursors.encodeOffset(offset + limit) : Cursors.encode(0, page.getLast().id());
        return new CursorPage<>(summaries(page, user), next);
    }

    @Transactional(readOnly = true)
    public Home home(AuthUser user) {
        long v = viewer(user);
        List<Row> featured = jdbc.sql("SELECT " + COLS + " FROM podcast_episodes e JOIN communities c ON c.id = e.community_id WHERE "
                        + VISIBLE + " AND e.published_at > now() - interval '30 days' ORDER BY e.play_count DESC, e.id DESC LIMIT 3")
                .param("viewer", v).query(this::row).list();
        List<Row> recent = jdbc.sql("SELECT " + COLS + " FROM podcast_episodes e JOIN communities c ON c.id = e.community_id WHERE "
                        + VISIBLE + " ORDER BY e.published_at DESC, e.id DESC LIMIT 12")
                .param("viewer", v).query(this::row).list();
        List<Row> cont = List.of();
        List<Row> saved = List.of();
        if (user != null) {
            cont = jdbc.sql("SELECT " + COLS + """
                             FROM podcast_episodes e JOIN communities c ON c.id = e.community_id
                            JOIN podcast_progress pp ON pp.episode_id = e.id AND pp.user_id = :viewer
                            WHERE pp.completed = FALSE AND pp.position_seconds > 0 AND """ + " " + VISIBLE + " ORDER BY pp.updated_at DESC LIMIT 8")
                    .param("viewer", v).query(this::row).list();
            saved = jdbc.sql("SELECT " + COLS + """
                             FROM podcast_episodes e JOIN communities c ON c.id = e.community_id
                            JOIN podcast_saves s ON s.episode_id = e.id AND s.user_id = :viewer WHERE """ + " " + VISIBLE + " ORDER BY s.created_at DESC LIMIT 12")
                    .param("viewer", v).query(this::row).list();
        }
        return new Home(summaries(featured, user), summaries(cont, user), summaries(recent, user), summaries(saved, user));
    }

    @Transactional(readOnly = true)
    public CursorPage<EpisodeSummary> history(AuthUser user, String cursor, int limit) {
        int offset = Cursors.decodeOffset(cursor);
        List<Row> rows = jdbc.sql("SELECT " + COLS + """
                         FROM podcast_episodes e JOIN communities c ON c.id = e.community_id
                        JOIN podcast_progress pp ON pp.episode_id = e.id AND pp.user_id = :viewer WHERE """ + " " + VISIBLE
                        + " ORDER BY pp.updated_at DESC OFFSET :off LIMIT :lim")
                .param("viewer", user.id()).param("off", offset).param("lim", limit + 1).query(this::row).list();
        boolean more = rows.size() > limit;
        return new CursorPage<>(summaries(more ? rows.subList(0, limit) : rows, user), more ? Cursors.encodeOffset(offset + limit) : null);
    }

    @Transactional(readOnly = true)
    public List<EpisodeSummary> studio(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        return summaries(jdbc.sql("SELECT " + COLS + " FROM podcast_episodes e WHERE e.community_id = ? ORDER BY e.updated_at DESC")
                .param(c.getId()).query(this::row).list(), user);
    }

    private List<EpisodeSummary> summaries(List<Row> rows, AuthUser user) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(Row::id).toList();
        Map<Long, Community> cs = new HashMap<>();
        communities.findAllById(rows.stream().map(Row::communityId).distinct().toList()).forEach(c -> cs.put(c.getId(), c));
        Map<Long, Integer> chapters = new HashMap<>();
        jdbc.sql("SELECT episode_id, count(*) FROM podcast_chapters WHERE episode_id IN (:ids) GROUP BY episode_id").param("ids", ids)
                .query((rs, i) -> chapters.put(rs.getLong(1), rs.getInt(2))).list();
        Map<Long, Progress> progress = new HashMap<>();
        Set<Long> saved = new HashSet<>();
        if (user != null) {
            jdbc.sql("SELECT episode_id, position_seconds, completed FROM podcast_progress WHERE user_id = :u AND episode_id IN (:ids)")
                    .param("u", user.id()).param("ids", ids)
                    .query((rs, i) -> progress.put(rs.getLong(1), new Progress(rs.getInt(2), rs.getBoolean(3)))).list();
            saved.addAll(jdbc.sql("SELECT episode_id FROM podcast_saves WHERE user_id = :u AND episode_id IN (:ids)")
                    .param("u", user.id()).param("ids", ids).query(Long.class).list());
        }
        return rows.stream().filter(r -> cs.containsKey(r.communityId())).map(r -> new EpisodeSummary(r.id(),
                CommunityMapper.ref(cs.get(r.communityId())), r.title(), r.summary(), r.type(), r.mediaKind(), r.duration(),
                r.thumbnailUrl(), r.status(), r.publishedAt(), r.createdAt(), r.playCount(), saved.contains(r.id()),
                progress.get(r.id()), chapters.getOrDefault(r.id(), 0), r.origin())).toList();
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public EpisodeDetail detail(long id, AuthUser user) {
        Row r = load(id);
        Community c = requireVisible(r, user);
        boolean manager = access.canModerate(access.roleOf(c, user), user);
        EpisodeSummary s = summaries(List.of(r), user).getFirst();
        List<Chapter> chapters = jdbc.sql("SELECT id, start_seconds, title FROM podcast_chapters WHERE episode_id = ? ORDER BY start_seconds, id")
                .param(id).query((rs, i) -> new Chapter(rs.getLong(1), rs.getInt(2), rs.getString(3))).list();
        boolean hasTranscript = jdbc.sql("SELECT EXISTS (SELECT 1 FROM podcast_transcript_segments WHERE episode_id = ?)").param(id)
                .query(Boolean.class).single();
        Map<Long, UserSummary> people = users.summaries(Stream.of(r.createdBy(), r.publishedBy()).filter(Objects::nonNull).toList());
        return new EpisodeDetail(s.id(), s.community(), s.title(), s.summary(), s.type(), s.mediaKind(), s.durationSeconds(),
                s.thumbnailUrl(), s.status(), s.publishedAt(), s.createdAt(), s.playCount(), s.saved(), s.progress(), s.chapterCount(),
                r.mediaKey() == null ? null : storage.publicUrl(r.mediaKey()), r.mediaContentType(), r.allowDownload(), chapters,
                sources(id, user), hasTranscript, hasTranscript ? "/api/v1/podcasts/episodes/" + id + "/transcript.vtt" : null,
                manager ? r.script() : null, r.scriptStatus(), r.scriptSource(), manager ? r.scriptError() : null,
                r.createdBy() == null ? null : people.get(r.createdBy()), r.publishedBy() == null ? null : people.get(r.publishedBy()),
                new EpisodeViewer(manager), r.origin(), "AI_TTS".equals(r.origin()) ? r.audioProvider() : null,
                "AI_TTS".equals(r.origin()) ? r.audioModel() : null);
    }

    /** Cited discussions, re-checked against the viewer's access on every read and skipped once deleted or removed. */
    private List<SourceRef> sources(long episodeId, AuthUser user) {
        return jdbc.sql("""
                        SELECT s.position, s.post_id, s.comment_id, p.title, c.slug, coalesce(cm.body, p.body_text) AS body
                        FROM podcast_sources s JOIN posts p ON p.id = s.post_id JOIN communities c ON c.id = p.community_id
                        LEFT JOIN comments cm ON cm.id = s.comment_id
                        WHERE s.episode_id = :e AND p.deleted_at IS NULL AND p.removed = FALSE
                          AND (cm.id IS NULL OR (cm.deleted_at IS NULL AND cm.removed = FALSE))
                          AND (c.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members vm WHERE vm.community_id = c.id AND vm.user_id = :viewer))
                        ORDER BY s.position""")
                .param("e", episodeId).param("viewer", viewer(user))
                .query((rs, i) -> {
                    Long cid = (Long) rs.getObject(3);
                    String slug = rs.getString(5);
                    return new SourceRef(rs.getInt(1), rs.getLong(2), cid, rs.getString(4), slug, TextSignals.excerpt(rs.getString(6), 200),
                            "/c/" + slug + "/p/" + rs.getLong(2) + (cid == null ? "" : "#c-" + cid));
                }).list();
    }

    @Transactional(readOnly = true)
    public List<Segment> transcript(long id, AuthUser user, String q) {
        requireVisible(load(id), user);
        String like = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                        SELECT id, start_seconds, end_seconds, speaker, text FROM podcast_transcript_segments
                        WHERE episode_id = :e AND (CAST(:q AS text) IS NULL OR lower(text) LIKE CAST(:q AS text) OR lower(coalesce(speaker, '')) LIKE CAST(:q AS text))
                        ORDER BY start_seconds, id""")
                .param("e", id).param("q", like)
                .query((rs, i) -> new Segment(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getString(5))).list();
    }

    @Transactional(readOnly = true)
    public String transcriptVtt(long id, AuthUser user) {
        List<TranscriptParser.Segment> segs = transcript(id, user, null).stream()
                .map(s -> new TranscriptParser.Segment(s.startSeconds(), s.endSeconds(), s.speaker(), s.text())).toList();
        return TranscriptParser.toVtt(segs);
    }

    // ------------------------------------------------------------------ listener state

    @Transactional
    public void progress(long id, AuthUser user, int position, Boolean completed) {
        Row r = load(id);
        requireVisible(r, user);
        if (!"PUBLISHED".equals(r.status())) return; // managers previewing drafts don't create listening history
        int capped = r.duration() == null ? position : Math.min(position, r.duration());
        boolean done = Boolean.TRUE.equals(completed) || (r.duration() != null && capped >= r.duration() - 5);
        int inserted = jdbc.sql("""
                        INSERT INTO podcast_progress (user_id, episode_id, position_seconds, completed) VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, episode_id) DO NOTHING""")
                .params(user.id(), id, capped, done).update();
        if (inserted > 0) {
            jdbc.sql("UPDATE podcast_episodes SET play_count = play_count + 1 WHERE id = ?").param(id).update();
        } else {
            jdbc.sql("UPDATE podcast_progress SET position_seconds = ?, completed = completed OR ?, updated_at = now() WHERE user_id = ? AND episode_id = ?")
                    .params(capped, done, user.id(), id).update();
        }
    }

    @Transactional
    public void save(long id, AuthUser user, boolean save) {
        requireVisible(load(id), user);
        if (save) jdbc.sql("INSERT INTO podcast_saves (user_id, episode_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), id).update();
        else jdbc.sql("DELETE FROM podcast_saves WHERE user_id = ? AND episode_id = ?").params(user.id(), id).update();
    }

    // ------------------------------------------------------------------ studio

    @Transactional
    public long create(String slug, AuthUser user, CreateEpisode req) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        limiter.check("podcast:create:" + user.id(), 30, Duration.ofDays(1));
        return jdbc.sql("INSERT INTO podcast_episodes (community_id, created_by, title, summary, type) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(c.getId(), user.id(), req.title().strip(), req.summary() == null ? "" : req.summary().strip(), req.type())
                .query(Long.class).single();
    }

    @Transactional
    public void update(long id, AuthUser user, UpdateEpisode req) {
        Row r = load(id);
        requireManager(r, user);
        if (req.title() != null) set(id, "title", req.title().strip());
        if (req.summary() != null) set(id, "summary", req.summary().strip());
        if (req.type() != null) set(id, "type", req.type());
        if (req.allowDownload() != null) jdbc.sql("UPDATE podcast_episodes SET allow_download = ? WHERE id = ?").params(req.allowDownload(), id).update();
        if (req.script() != null) {
            jdbc.sql("UPDATE podcast_episodes SET script = ?, script_source = CASE WHEN script_source = 'AI_DRAFT' THEN 'AI_DRAFT' ELSE 'HUMAN' END, "
                            + "script_status = CASE WHEN ? = '' THEN 'NONE' ELSE 'READY' END WHERE id = ? AND script_status <> 'GENERATING'")
                    .params(req.script(), req.script().isBlank() ? "" : "x", id).update();
        }
        if (req.chapters() != null) replaceChapters(id, req.chapters(), r.duration());
        if (req.sources() != null) replaceSources(id, req.sources(), user);
        touch(id);
    }

    void replaceChapters(long id, List<ChapterInput> chapters, Integer duration) {
        jdbc.sql("DELETE FROM podcast_chapters WHERE episode_id = ?").param(id).update();
        Set<Integer> starts = new HashSet<>();
        for (ChapterInput ch : chapters) {
            if (duration != null && ch.startSeconds() > duration) throw ApiException.field("chapters", "A chapter starts after the episode ends");
            if (!starts.add(ch.startSeconds())) throw ApiException.field("chapters", "Two chapters start at the same time");
            jdbc.sql("INSERT INTO podcast_chapters (episode_id, start_seconds, title) VALUES (?, ?, ?)").params(id, ch.startSeconds(), ch.title().strip()).update();
        }
    }

    /** Sources must be discussions the editor can actually see; comments must belong to the given post. */
    void replaceSources(long id, List<SourceInput> sources, AuthUser user) {
        jdbc.sql("DELETE FROM podcast_sources WHERE episode_id = ?").param(id).update();
        Set<String> seen = new HashSet<>();
        int pos = 0;
        for (SourceInput s : sources) {
            if (!seen.add(s.postId() + ":" + s.commentId())) continue;
            posts.context(s.postId(), user);
            if (s.commentId() != null) {
                Long owner = jdbc.sql("SELECT post_id FROM comments WHERE id = ?").param(s.commentId()).query(Long.class).optional().orElse(null);
                if (!Objects.equals(owner, s.postId())) throw ApiException.field("sources", "Comment " + s.commentId() + " is not on post " + s.postId());
            }
            jdbc.sql("INSERT INTO podcast_sources (episode_id, position, post_id, comment_id) VALUES (?, ?, ?, ?)")
                    .params(id, pos++, s.postId(), s.commentId()).update();
        }
    }

    @Transactional
    public void uploadMedia(long id, AuthUser user, MultipartFile file, Integer durationSeconds) {
        Row r = load(id);
        requireManager(r, user);
        if ("PUBLISHED".equals(r.status())) throw ApiException.badRequest("Unpublish the episode before replacing its media");
        if (audioJobActive(id)) throw ApiException.conflict(null, "Audio is being generated for this episode. Cancel it before uploading");
        limiter.check("podcast:upload:" + user.id(), 20, Duration.ofHours(1));
        if (file == null || file.isEmpty()) throw ApiException.field("file", "Choose a file to upload");
        if (file.getSize() > maxMediaBytes) throw ApiException.field("file", "Media files must be under " + maxUploadMb() + " MB");
        if (durationSeconds == null || durationSeconds < 1 || durationSeconds > 6 * 3600) {
            throw ApiException.field("durationSeconds", "The media's duration couldn't be read (1 second to 6 hours supported)");
        }
        String key;
        MediaSniffer.Kind kind;
        try (PushbackInputStream in = new PushbackInputStream(file.getInputStream(), 16)) {
            byte[] head = in.readNBytes(16);
            kind = MediaSniffer.sniff(head);
            in.unread(head);
            key = "podcasts/" + Tokens.random(18) + "." + kind.extension();
            storage.put(key, (InputStream) in, kind.contentType());
        } catch (IOException e) {
            throw ApiException.badRequest("Upload failed");
        }
        // Malware scan of the stored bytes when ClamAV is configured (D-029); refused media is removed again.
        String stored = key;
        try {
            antivirus.check(() -> storage.get(stored).orElseThrow(() -> new IOException("stored object missing")).stream(), "media file");
        } catch (ApiException e) {
            storage.delete(key);
            throw e;
        }
        jdbc.sql("""
                        UPDATE podcast_episodes SET media_kind = ?, media_key = ?, media_content_type = ?, media_bytes = ?, duration_seconds = ?,
                               audio_origin = 'UPLOADED', audio_provider = NULL, audio_model = NULL
                        WHERE id = ?""")
                .params(kind.mediaKind(), key, kind.contentType(), file.getSize(), durationSeconds, id).update();
        if (r.mediaKey() != null) storage.delete(r.mediaKey());
        touch(id);
    }

    @Transactional
    public void uploadThumbnail(long id, AuthUser user, MultipartFile file) {
        Row r = load(id);
        requireManager(r, user);
        var img = media.storeImage(file, "podcasts", 5L * 1024 * 1024);
        set(id, "thumbnail_url", img.url());
        touch(id);
    }

    @Transactional
    public int replaceTranscript(long id, AuthUser user, String format, String content) {
        Row r = load(id);
        requireManager(r, user);
        if ("PUBLISHED".equals(r.status())) throw ApiException.badRequest("Unpublish the episode before replacing its transcript");
        List<TranscriptParser.Segment> segs = TranscriptParser.parse(format, content, r.duration());
        storeTranscript(id, segs);
        touch(id);
        return segs.size();
    }

    /** Replaces the episode's transcript (callers check permissions and the published lock). */
    void storeTranscript(long id, List<TranscriptParser.Segment> segs) {
        jdbc.sql("DELETE FROM podcast_transcript_segments WHERE episode_id = ?").param(id).update();
        for (TranscriptParser.Segment s : segs) {
            jdbc.sql("INSERT INTO podcast_transcript_segments (episode_id, start_seconds, end_seconds, speaker, text) VALUES (?, ?, ?, ?, ?)")
                    .params(id, s.start(), s.end(), s.speaker(), s.text()).update();
        }
    }

    boolean audioJobActive(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM podcast_tts_jobs WHERE episode_id = ? AND status IN ('QUEUED', 'GENERATING'))")
                .param(id).query(Boolean.class).single();
    }

    @Transactional
    public void transition(long id, AuthUser user, String action) {
        Row r = load(id);
        requireManager(r, user);
        switch (action) {
            case "submit" -> {
                if (!Set.of("DRAFT", "ARCHIVED").contains(r.status())) throw ApiException.badRequest("Only drafts can be submitted for review");
                setStatus(id, "IN_REVIEW");
            }
            case "publish" -> {
                if (!"IN_REVIEW".equals(r.status())) throw ApiException.badRequest("Submit the episode for review before publishing");
                if (r.mediaKey() == null) throw ApiException.badRequest("Upload the episode's audio or video first");
                boolean transcript = jdbc.sql("SELECT EXISTS (SELECT 1 FROM podcast_transcript_segments WHERE episode_id = ?)").param(id)
                        .query(Boolean.class).single();
                if (!transcript) throw ApiException.badRequest("Add a transcript before publishing");
                jdbc.sql("UPDATE podcast_episodes SET status = 'PUBLISHED', published_by = ?, published_at = coalesce(published_at, now()) WHERE id = ?")
                        .params(user.id(), id).update();
            }
            case "unpublish" -> {
                if (!"PUBLISHED".equals(r.status())) throw ApiException.badRequest("This episode isn't published");
                setStatus(id, "ARCHIVED");
            }
            default -> throw ApiException.notFound("Action");
        }
        touch(id);
    }

    @Transactional
    public void delete(long id, AuthUser user) {
        Row r = load(id);
        requireManager(r, user);
        jdbc.sql("DELETE FROM podcast_episodes WHERE id = ?").param(id).update();
        if (r.mediaKey() != null) storage.delete(r.mediaKey());
    }

    private void setStatus(long id, String status) {
        jdbc.sql("UPDATE podcast_episodes SET status = ? WHERE id = ?").params(status, id).update();
    }

    private void set(long id, String column, String value) {
        // column names are constants from this class only
        jdbc.sql("UPDATE podcast_episodes SET " + column + " = ? WHERE id = ?").params(value, id).update();
    }

    private void touch(long id) {
        jdbc.sql("UPDATE podcast_episodes SET updated_at = now() WHERE id = ?").param(id).update();
    }

    private static long viewer(AuthUser user) {
        return user == null ? -1L : user.id();
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
