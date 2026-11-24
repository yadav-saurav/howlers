package app.howlers.reaction;

import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.messaging.RoomService;
import app.howlers.messaging.DmService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.reaction.ReactionService.TargetType;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import app.howlers.support.TestIds;

/** Reactions arrive live on the thread topic and, for messages, on each member's queue (real STOMP clients). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReactionsLiveIT extends IntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired ReactionService reactions;
    @Autowired RoomService rooms;
    @Autowired DmService dms;

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
        var res = auth.register(new RegisterRequest(name, name + "@rxlive.local", "password1", null), "t", "127.0.0.1").response();
        return new Registered(new AuthUser(res.user().id(), res.user().username(), res.user().role()), res.accessToken());
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }

    private static BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
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

    private static JsonNode next(BlockingQueue<JsonNode> q, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode n = q.poll(500, TimeUnit.MILLISECONDS);
            if (n != null && type.equals(n.path("type").asText())) return n;
        }
        return null;
    }

    @Test
    void reactionChangesArriveOnTheThreadTopicAndMemberQueues() throws Exception {
        Registered ana = user("ana"), ben = user("ben"), sol = user("sol");
        String slug = "rxl" + TestIds.suffix();
        communities.create(ana.user(), new CreateCommunityRequest(slug, "Live reactions", "", null, "PUBLIC", List.of(), List.of()));
        long postId = posts.create(ana.user(), new CreatePostRequest(slug, "TEXT", "Live", "<p>x</p>", null, null, null, null, null));

        StompSession benSession = connect(ben.token());
        BlockingQueue<JsonNode> thread = subscribe(benSession, "/topic/post." + postId);
        BlockingQueue<JsonNode> queue = subscribe(benSession, "/user/queue/events");
        Thread.sleep(300);

        reactions.set(sol.user(), TargetType.POST, postId, "fire", true);
        JsonNode e = next(thread, "REACTIONS");
        assertThat(e).isNotNull();
        assertThat(e.get("targetType").asText()).isEqualTo("POST");
        assertThat(e.get("targetId").asLong()).isEqualTo(postId);
        assertThat(e.has("userId")).isFalse(); // ids only, never who

        long room = rooms.create(ana.user(), "Live pack", null, List.of(ben.user().username(), sol.user().username())).id();
        long msg = dms.send(room, ana.user(), new SendRequest("react to me", null)).id();
        reactions.set(sol.user(), TargetType.MESSAGE, msg, "howl", true);
        JsonNode m = next(queue, "REACTIONS");
        assertThat(m).isNotNull();
        assertThat(m.get("payload").get("targetId").asLong()).isEqualTo(msg);
        assertThat(m.get("payload").get("conversationId").asLong()).isEqualTo(room);
    }
}
