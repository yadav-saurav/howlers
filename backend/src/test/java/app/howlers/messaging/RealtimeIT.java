package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.vote.VoteService;
import java.util.List;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import app.howlers.support.TestIds;

/** Talks to the real /ws endpoint with a STOMP client: auth on CONNECT, user-queue delivery, subscription checks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeIT extends IntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired DmService dms;
    @Autowired ObjectMapper mapper;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired VoteService votes;

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());

    {
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void stop() {
        client.stop();
    }

    private record Registered(AuthUser user, String token) {}

    private Registered user(String prefix) {
        String name = prefix + TestIds.suffix();
        var res = auth.register(new RegisterRequest(name, name + "@rt.local", "password1", null), "t", "127.0.0.1").response();
        return new Registered(new AuthUser(res.user().id(), res.user().username(), res.user().role()), res.accessToken());
    }

    /** Records ERROR frames and transport failures; the server closes the session after an ERROR. */
    private static final class Errors extends StompSessionHandlerAdapter {
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(String.valueOf(headers.getFirst("message")));
        }

        @Override
        public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable e) {
            errors.add(e.toString());
        }

        @Override
        public void handleTransportError(StompSession session, Throwable e) {
            errors.add(e.toString());
        }
    }

    private StompSession connect(String token, Errors handler) throws Exception {
        StompHeaders connect = new StompHeaders();
        if (token != null) connect.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, handler)
                .get(10, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((JsonNode) payload);
            }
        });
        return frames;
    }

    @Test
    void messagesArriveLiveOnTheRecipientsQueue() throws Exception {
        Registered ana = user("ana"), ben = user("ben");
        StompSession benSession = connect(ben.token(), new Errors());
        BlockingQueue<JsonNode> benEvents = subscribe(benSession, "/user/queue/events");
        Thread.sleep(300); // let the SUBSCRIBE register before publishing

        var convo = dms.start(ana.user(), ben.user().username());
        dms.send(convo.id(), ana.user(), new SendRequest("hello over the wire", null));

        JsonNode first = benEvents.poll(10, TimeUnit.SECONDS);
        JsonNode second = benEvents.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        Map<String, JsonNode> byType = Map.of(first.get("type").asText(), first, second.get("type").asText(), second);
        assertThat(byType.get("MESSAGE").get("payload").get("body").asText()).isEqualTo("hello over the wire");
        assertThat(byType.get("UNREAD_MESSAGES").get("payload").get("count").asLong()).isEqualTo(1);

        // Typing goes through /app and reaches only the other participant.
        StompSession anaSession = connect(ana.token(), new Errors());
        anaSession.send("/app/typing", Map.of("conversationId", convo.id()));
        JsonNode typing = benEvents.poll(10, TimeUnit.SECONDS);
        assertThat(typing).isNotNull();
        // Ana connecting also produces a PRESENCE event; skip it if it arrived first.
        if ("PRESENCE".equals(typing.get("type").asText())) typing = benEvents.poll(10, TimeUnit.SECONDS);
        assertThat(typing.get("type").asText()).isEqualTo("TYPING");
        assertThat(typing.get("payload").get("userId").asLong()).isEqualTo(ana.user().id());
    }

    @Test
    void connectWithoutAValidTokenIsRejected() {
        Errors handler = new Errors();
        try {
            connect("not-a-token", handler);
            // Depending on timing the future may complete before the ERROR frame; the handler must then see it.
            assertThat(handler.errors.poll(10, TimeUnit.SECONDS)).isNotNull();
        } catch (Exception e) {
            assertThat(e).isInstanceOfAny(ExecutionException.class, java.util.concurrent.TimeoutException.class);
        }
    }

    @Test
    void subscribingToSomeoneElsesModQueueIsRefused() throws Exception {
        Registered eve = user("eve");
        Errors handler = new Errors();
        StompSession session = connect(eve.token(), handler);
        subscribe(session, "/topic/modqueue.1");
        String error = handler.errors.poll(10, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error).contains("Not allowed");
    }

    /** Polls the queue until a frame of the given type arrives (other event types may interleave). */
    private static JsonNode next(BlockingQueue<JsonNode> q, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode n = q.poll(500, TimeUnit.MILLISECONDS);
            if (n != null && type.equals(n.path("type").asText())) return n;
        }
        return null;
    }

    private String community(Registered owner, String visibility) {
        String slug = "live" + TestIds.suffix();
        communities.create(owner.user(), new CreateCommunityRequest(slug, "Live test", "", null, visibility, List.of(), List.of()));
        return slug;
    }

    private long post(Registered author, String slug, String title) {
        return posts.create(author.user(), new CreatePostRequest(slug, "TEXT", title, "<p>body</p>", null, null, null, null, null));
    }

    @Test
    void newPostsCommentsAndScoresArriveLive() throws Exception {
        Registered ana = user("ana"), ben = user("ben");
        String slug = community(ana, "PUBLIC");
        long communityId = communities.detail(slug, ana.user()).id();
        communities.join(slug, ben.user());

        StompSession benSession = connect(ben.token(), new Errors());
        BlockingQueue<JsonNode> site = subscribe(benSession, "/topic/posts");
        BlockingQueue<JsonNode> community = subscribe(benSession, "/topic/community." + communityId);
        Thread.sleep(300);

        long postId = post(ana, slug, "Live post one");
        JsonNode created = next(community, "POST_CREATED");
        assertThat(created).isNotNull();
        assertThat(created.get("postId").asLong()).isEqualTo(postId);
        assertThat(created.get("communitySlug").asText()).isEqualTo(slug);
        assertThat(created.get("authorId").asLong()).isEqualTo(ana.user().id());
        assertThat(next(site, "POST_CREATED")).isNotNull();

        BlockingQueue<JsonNode> thread = subscribe(benSession, "/topic/post." + postId);
        Thread.sleep(300);
        long commentId = comments.create(postId, ana.user(), new CreateCommentRequest("First!", null)).id();
        JsonNode c = next(thread, "COMMENT_CREATED");
        assertThat(c).isNotNull();
        assertThat(c.get("commentId").asLong()).isEqualTo(commentId);
        assertThat(c.get("parentId").isNull()).isTrue();
        // The announced comment can be fetched on its own.
        assertThat(comments.one(commentId, ben.user()).body()).isEqualTo("First!");

        votes.votePost(postId, ben.user(), 1);
        votes.voteComment(commentId, ben.user(), 1);
        JsonNode stats = null;
        for (int i = 0; i < 3 && (stats == null || stats.get("score").asInt() < 1); i++) stats = next(thread, "POST_STATS");
        assertThat(stats).isNotNull();
        assertThat(stats.get("score").asInt()).isEqualTo(1);
        assertThat(stats.get("commentCount").asInt()).isEqualTo(1);
        JsonNode scores = next(thread, "COMMENT_SCORES");
        assertThat(scores).isNotNull();
        assertThat(scores.get("scores").get(Long.toString(commentId)).asInt()).isEqualTo(1);
    }

    @Test
    void privateCommunitiesStayPrivate() throws Exception {
        Registered owner = user("own"), eve = user("eve");
        String slug = community(owner, "PRIVATE");
        long communityId = communities.detail(slug, owner.user()).id();

        StompSession eveSession = connect(eve.token(), new Errors());
        BlockingQueue<JsonNode> site = subscribe(eveSession, "/topic/posts");
        Thread.sleep(300);
        long postId = post(owner, slug, "Members only");
        assertThat(next(site, "POST_CREATED")).isNull(); // private posts never reach the site-wide topic
        assertThatThrownBy(() -> comments.one(
                comments.create(postId, owner.user(), new CreateCommentRequest("secret", null)).id(), eve.user()))
                .isInstanceOf(ApiException.class);

        for (String dest : List.of("/topic/community." + communityId, "/topic/post." + postId)) {
            Errors handler = new Errors();
            StompSession s = connect(eve.token(), handler);
            subscribe(s, dest);
            String error = handler.errors.poll(10, TimeUnit.SECONDS);
            assertThat(error).as(dest).isNotNull().contains("Not allowed");
        }
    }
}
