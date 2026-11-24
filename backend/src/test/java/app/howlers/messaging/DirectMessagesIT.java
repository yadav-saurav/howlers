package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.DmMessage;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.moderation.ModerationService;
import app.howlers.support.IntegrationTest;
import app.howlers.user.UserService;
import app.howlers.user.dto.UserDtos.UpdateSettingsRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DirectMessagesIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired DmService dms;
    @Autowired UserService users;
    @Autowired ModerationService moderation;
    @Autowired JdbcClient jdbc;

    private AuthUser user(String prefix) {
        String name = prefix + (System.nanoTime() % 1_000_000);
        var me = auth.register(new RegisterRequest(name, name + "@dm.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private static SendRequest text(String body) {
        return new SendRequest(body, null);
    }

    @Test
    void conversationLifecycleWithUnreadCountsReceiptsAndDeletion() {
        AuthUser ana = user("ana"), ben = user("ben");
        var convo = dms.start(ana, ben.username());
        assertThat(dms.start(ana, ben.username()).id()).isEqualTo(convo.id()); // get-or-create is idempotent
        assertThat(dms.start(ben, ana.username()).id()).isEqualTo(convo.id()); // from either side
        assertThat(dms.list(ben, null)).isEmpty(); // empty conversations stay out of the inbox

        DmMessage hi = dms.send(convo.id(), ana, text("  Hi Ben,\r\nshall we pair on the parser?\u0007 "));
        assertThat(hi.body()).isEqualTo("Hi Ben,\nshall we pair on the parser?");
        dms.send(convo.id(), ana, text("Second message"));
        assertThat(dms.unreadCount(ben.id())).isEqualTo(2);
        assertThat(dms.unreadCount(ana.id())).isZero(); // your own messages are never unread

        var inbox = dms.list(ben, null);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().other().username()).isEqualTo(ana.username());
        assertThat(inbox.getFirst().unreadCount()).isEqualTo(2);
        assertThat(inbox.getFirst().lastMessage().excerpt()).isEqualTo("Second message");
        assertThat(dms.list(ben, "parser")).hasSize(1);
        assertThat(dms.list(ben, "nothing-matches-this")).isEmpty();

        dms.markRead(convo.id(), ben, hi.id());
        assertThat(dms.unreadCount(ben.id())).isEqualTo(1);
        assertThat(dms.get(convo.id(), ana).otherLastReadMessageId()).isEqualTo(hi.id()); // "Seen" receipt for Ana

        var page = dms.messages(convo.id(), ben, null, 1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(DmMessage::body).containsExactly("Second message");
        assertThat(dms.messages(convo.id(), ben, page.items().getFirst().id(), 30).items()).extracting(DmMessage::id).containsExactly(hi.id());

        assertThatThrownBy(() -> dms.delete(hi.id(), ben)).isInstanceOf(ApiException.class); // not the sender
        dms.delete(hi.id(), ana);
        DmMessage gone = dms.messages(convo.id(), ben, null, 30).items().getFirst();
        assertThat(gone.deleted()).isTrue();
        assertThat(gone.body()).isEmpty();
    }

    @Test
    void outsidersCannotReadOrWriteAConversation() {
        AuthUser ana = user("ana"), ben = user("ben"), eve = user("eve");
        var convo = dms.start(ana, ben.username());
        dms.send(convo.id(), ana, text("private"));
        assertThatThrownBy(() -> dms.messages(convo.id(), eve, null, 30)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.send(convo.id(), eve, text("let me in"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.get(convo.id(), eve)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.start(ana, ana.username())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.send(convo.id(), ana, text("   "))).isInstanceOf(ApiException.class);
    }

    @Test
    void attachmentsMustBeYourOwnImages() {
        AuthUser ana = user("ana"), ben = user("ben");
        var convo = dms.start(ana, ben.username());
        long bensImage = jdbc.sql("""
                        INSERT INTO media_assets (owner_id, storage_key, content_type, size_bytes, width, height)
                        VALUES (?, 'media/test-ben.png', 'image/png', 10, 4, 4) RETURNING id""")
                .param(ben.id()).query(Long.class).single();
        long anasImage = jdbc.sql("""
                        INSERT INTO media_assets (owner_id, storage_key, content_type, size_bytes, width, height)
                        VALUES (?, 'media/test-ana.png', 'image/png', 10, 4, 4) RETURNING id""")
                .param(ana.id()).query(Long.class).single();
        assertThatThrownBy(() -> dms.send(convo.id(), ana, new SendRequest(null, List.of(bensImage))))
                .isInstanceOf(ApiException.class);
        DmMessage sent = dms.send(convo.id(), ana, new SendRequest(null, List.of(anasImage)));
        assertThat(sent.media()).hasSize(1);
        assertThat(sent.media().getFirst().width()).isEqualTo(4);
        assertThat(dms.list(ben, null).getFirst().lastMessage().excerpt()).isEqualTo("Sent an image");
    }

    @Test
    void blocksAndDmPolicyAreEnforced() {
        AuthUser ana = user("ana"), ben = user("ben"), cy = user("cy");

        // Blocking (either direction) stops new and existing conversations.
        var convo = dms.start(ana, ben.username());
        moderation.block(ben, ana.username(), true);
        assertThatThrownBy(() -> dms.send(convo.id(), ana, text("hello?"))).isInstanceOf(ApiException.class);
        assertThat(dms.get(convo.id(), ana).canMessage()).isFalse();
        assertThat(users.profile(ben.username(), ana).canMessage()).isFalse();
        moderation.block(ben, ana.username(), false);
        assertThat(dms.send(convo.id(), ana, text("hello again")).id()).isPositive();

        // NOBODY: no one can start a conversation...
        users.updateSettings(cy, new UpdateSettingsRequest(null, null, null, null, null, "NOBODY"));
        assertThatThrownBy(() -> dms.start(ana, cy.username())).isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't accepting messages");
        assertThat(users.profile(cy.username(), ana).canMessage()).isFalse();

        // FOLLOWING: only people cy follows.
        users.updateSettings(cy, new UpdateSettingsRequest(null, null, null, null, null, "FOLLOWING"));
        assertThatThrownBy(() -> dms.start(ana, cy.username())).isInstanceOf(ApiException.class);
        users.follow(cy, ana.username());
        var withCy = dms.start(ana, cy.username());
        dms.send(withCy.id(), ana, text("thanks for the follow"));

        // ...but once cy has written to someone, that person can always reply.
        dms.send(withCy.id(), cy, text("welcome!"));
        users.updateSettings(cy, new UpdateSettingsRequest(null, null, null, null, null, "NOBODY"));
        assertThat(dms.send(withCy.id(), ana, text("a reply is still fine")).id()).isPositive();
        assertThat(users.me(cy).settings().dmPolicy()).isEqualTo("NOBODY");
    }
}
