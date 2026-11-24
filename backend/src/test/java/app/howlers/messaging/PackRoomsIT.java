package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.ConversationSummary;
import app.howlers.messaging.DmDtos.DmMessage;
import app.howlers.messaging.DmDtos.RoomMember;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.moderation.ModerationService;
import app.howlers.support.IntegrationTest;
import app.howlers.user.UserService;
import app.howlers.user.dto.UserDtos.UpdateSettingsRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import app.howlers.support.TestIds;

/** Pack rooms (D-043): membership, roles, privacy of removed members, blocks and dmPolicy, ownership. */
class PackRoomsIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired DmService dms;
    @Autowired RoomService rooms;
    @Autowired DmMediaUrls mediaUrls;
    @Autowired UserService users;
    @Autowired ModerationService moderation;
    @Autowired JdbcClient jdbc;

    private AuthUser user(String prefix) {
        String name = prefix + TestIds.suffix();
        var me = auth.register(new RegisterRequest(name, name + "@rooms.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private static SendRequest text(String body) {
        return new SendRequest(body, null);
    }

    private ConversationSummary room(AuthUser owner, AuthUser... others) {
        return rooms.create(owner, "Night owls", "🐺", java.util.Arrays.stream(others).map(AuthUser::username).toList());
    }

    @Test
    void creatingARoomNeedsThreePeopleAndWritesASystemMessage() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol");
        assertThatThrownBy(() -> rooms.create(mira, "Too small", null, List.of(dev.username())))
                .isInstanceOf(ApiException.class).hasMessageContaining("at least 2 other people");
        assertThatThrownBy(() -> rooms.create(mira, "Bad icon", "abc", List.of(dev.username(), sol.username())))
                .isInstanceOf(ApiException.class);

        ConversationSummary s = room(mira, dev, sol);
        assertThat(s.kind()).isEqualTo("ROOM");
        assertThat(s.other()).isNull();
        assertThat(s.room().name()).isEqualTo("Night owls");
        assertThat(s.room().iconEmoji()).isEqualTo("🐺");
        assertThat(s.room().memberCount()).isEqualTo(3);
        assertThat(s.room().myRole()).isEqualTo("OWNER");

        DmMessage created = dms.messages(s.id(), dev, null, 30).items().getFirst();
        assertThat(created.kind()).isEqualTo("SYSTEM");
        assertThat(created.body()).isEqualTo(mira.username() + " created the room “Night owls”");
        assertThat(dms.unreadCount(dev.id())).isZero(); // system lines are never "unread"

        // Rooms and one-to-one conversations share the inbox.
        var direct = dms.start(dev, sol.username());
        dms.send(direct.id(), dev, text("psst"));
        assertThat(dms.list(sol, null)).extracting(ConversationSummary::kind).containsExactlyInAnyOrder("DIRECT", "ROOM");
        assertThat(dms.list(sol, "night")).extracting(ConversationSummary::id).containsExactly(s.id());
    }

    @Test
    void nonMembersCanNotReadOrWriteAndRolesGateManagement() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol"), eve = user("eve"), kai = user("kai");
        ConversationSummary s = room(mira, dev, sol);
        long id = s.id();
        dms.send(id, mira, text("welcome, pack"));

        assertThatThrownBy(() -> dms.messages(id, eve, null, 30)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.send(id, eve, text("let me in"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.get(id, eve)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.detail(id, eve)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.addMembers(id, eve, List.of(eve.username()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.markRead(id, eve, 1)).isInstanceOf(ApiException.class);

        // Plain members can't manage the room.
        assertThatThrownBy(() -> rooms.addMembers(id, dev, List.of(kai.username()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.update(id, dev, "Renamed", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.removeMember(id, dev, sol.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.setRole(id, dev, sol.id(), "ADMIN")).isInstanceOf(ApiException.class);

        // The owner makes dev an admin; admins add people and rename, but can't remove the owner or other admins.
        rooms.setRole(id, mira, dev.id(), "ADMIN");
        var detail = rooms.addMembers(id, dev, List.of(kai.username()));
        assertThat(detail.members()).extracting(m -> m.user().username())
                .containsExactly(mira.username(), dev.username(), sol.username(), kai.username());
        assertThat(rooms.update(id, dev, "Pack HQ", "🎉").name()).isEqualTo("Pack HQ");
        assertThatThrownBy(() -> rooms.removeMember(id, dev, mira.id())).isInstanceOf(ApiException.class);
        rooms.setRole(id, mira, sol.id(), "ADMIN");
        assertThatThrownBy(() -> rooms.removeMember(id, dev, sol.id())).isInstanceOf(ApiException.class)
                .hasMessageContaining("Only the owner");

        // New members don't inherit an unread backlog, and the timeline records each change.
        assertThat(dms.unreadCount(kai.id())).isZero();
        List<String> lines = dms.messages(id, kai, null, 30).items().stream().filter(m -> "SYSTEM".equals(m.kind()))
                .map(DmMessage::body).toList();
        assertThat(lines).contains(mira.username() + " made " + dev.username() + " an admin",
                dev.username() + " added " + kai.username(), dev.username() + " renamed the room to “Pack HQ”");
    }

    @Test
    void removedMembersLoseNewMessagesAndSignedImageUrls() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol");
        long id = room(mira, dev, sol).id();
        long image = jdbc.sql("""
                        INSERT INTO media_assets (owner_id, storage_key, content_type, size_bytes, width, height)
                        VALUES (?, ?, 'image/png', 10, 4, 4) RETURNING id""")
                .params(mira.id(), "private/room-test-" + System.nanoTime() + ".png").query(Long.class).single();
        dms.send(id, mira, new SendRequest("look", List.of(image)));

        DmMessage solsView = dms.messages(id, sol, null, 30).items().getLast();
        assertThat(solsView.media()).hasSize(1);
        assertThat(solsView.media().getFirst().signed()).isTrue();
        String grant = solsView.media().getFirst().url().substring("/api/v1/dm-media/".length());
        assertThat(mediaUrls.resolve(grant)).isPresent();

        rooms.removeMember(id, mira, sol.id());
        assertThat(mediaUrls.resolve(grant)).isEmpty(); // the grant is re-checked against membership on every use
        dms.send(id, mira, text("after sol left"));
        assertThatThrownBy(() -> dms.messages(id, sol, null, 30)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dms.send(id, sol, text("still here?"))).isInstanceOf(ApiException.class);
        assertThat(dms.list(sol, null)).isEmpty();
        assertThat(dms.unreadCount(sol.id())).isZero();
        assertThat(rooms.detail(id, dev).members()).extracting(m -> m.user().id()).doesNotContain(sol.id());
        assertThat(dms.messages(id, dev, null, 30).items()).extracting(DmMessage::body)
                .contains(mira.username() + " removed " + sol.username(), "after sol left");
    }

    @Test
    void invitesRespectBlocksAndDmPolicyAndBlockedMembersAreCollapsed() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol"), cy = user("cy"), bo = user("bo");

        moderation.block(cy, mira.username(), true);
        assertThatThrownBy(() -> rooms.create(mira, "Pack", null, List.of(dev.username(), cy.username())))
                .isInstanceOf(ApiException.class).hasMessageContaining("u/" + cy.username());
        users.updateSettings(bo, new UpdateSettingsRequest(null, null, null, null, null, "NOBODY"));
        assertThatThrownBy(() -> rooms.create(mira, "Pack", null, List.of(dev.username(), bo.username())))
                .isInstanceOf(ApiException.class).hasMessageContaining("isn't accepting messages");
        users.updateSettings(bo, new UpdateSettingsRequest(null, null, null, null, null, "FOLLOWING"));
        assertThatThrownBy(() -> rooms.create(mira, "Pack", null, List.of(dev.username(), bo.username())))
                .isInstanceOf(ApiException.class);
        users.follow(bo, mira.username());

        long id = rooms.create(mira, "Pack", null, List.of(dev.username(), sol.username(), bo.username())).id();
        // A member who blocks another stays in the room but no longer sees their messages.
        moderation.block(sol, dev.username(), true);
        DmMessage fromDev = dms.send(id, dev, text("hello everyone"));
        DmMessage solSees = dms.messages(id, sol, null, 30).items().getLast();
        assertThat(solSees.id()).isEqualTo(fromDev.id());
        assertThat(solSees.fromBlocked()).isTrue();
        assertThat(solSees.body()).isEmpty();
        assertThat(dms.messages(id, bo, null, 30).items().getLast().body()).isEqualTo("hello everyone");
        assertThat(dms.unreadCount(sol.id())).isZero();
        assertThat(dms.unreadCount(bo.id())).isEqualTo(1);
        assertThat(dms.get(id, sol).lastMessage().excerpt()).isEqualTo("Message from someone you blocked");
        assertThat(dms.get(id, bo).lastMessage().excerpt()).isEqualTo(dev.username() + ": hello everyone");
    }

    @Test
    void readReceiptsAreTrackedPerMember() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol");
        long id = room(mira, dev, sol).id();
        DmMessage m = dms.send(id, mira, text("who's around?"));
        assertThat(dms.unreadCount(dev.id())).isEqualTo(1);
        assertThat(dms.unreadCount(sol.id())).isEqualTo(1);
        dms.markRead(id, dev, m.id());
        assertThat(dms.unreadCount(dev.id())).isZero();
        assertThat(dms.get(id, sol).unreadCount()).isEqualTo(1);
        List<RoomMember> members = rooms.detail(id, mira).members();
        assertThat(members.stream().filter(x -> x.user().id() == dev.id()).findFirst().orElseThrow().lastReadMessageId()).isEqualTo(m.id());
        Long solRead = members.stream().filter(x -> x.user().id() == sol.id()).findFirst().orElseThrow().lastReadMessageId();
        assertThat(solRead == null || solRead < m.id()).isTrue();

        // Soft delete works the same as in one-to-one conversations.
        assertThatThrownBy(() -> dms.delete(m.id(), dev)).isInstanceOf(ApiException.class);
        dms.delete(m.id(), mira);
        assertThat(dms.messages(id, sol, null, 30).items().getLast().deleted()).isTrue();
        assertThat(dms.unreadCount(sol.id())).isZero();
    }

    @Test
    void ownershipCanBeHandedOverAndTheLastOneOutClosesTheRoom() {
        AuthUser mira = user("mira"), dev = user("dev"), sol = user("sol");
        long id = room(mira, dev, sol).id();
        assertThatThrownBy(() -> rooms.transfer(id, dev, sol.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.delete(id, dev)).isInstanceOf(ApiException.class);

        rooms.transfer(id, mira, dev.id());
        var d = rooms.detail(id, mira);
        assertThat(d.myRole()).isEqualTo("ADMIN");
        assertThat(d.members().getFirst().user().id()).isEqualTo(dev.id());
        assertThat(d.members().getFirst().role()).isEqualTo("OWNER");

        // The owner leaving hands the room to the longest-standing admin.
        rooms.leave(id, dev);
        assertThat(rooms.detail(id, mira).myRole()).isEqualTo("OWNER");
        rooms.leave(id, sol);
        rooms.leave(id, mira);
        assertThat(jdbc.sql("SELECT count(*) FROM dm_conversations WHERE id = ?").param(id).query(Long.class).single()).isZero();

        // The owner can delete a room outright.
        long other = room(mira, dev, sol).id();
        rooms.delete(other, mira);
        assertThatThrownBy(() -> dms.get(other, dev)).isInstanceOf(ApiException.class);
    }

    @Test
    void oneToOneConversationsAreNotRooms() {
        AuthUser ana = user("ana"), ben = user("ben");
        var convo = dms.start(ana, ben.username());
        assertThat(convo.kind()).isEqualTo("DIRECT");
        assertThat(convo.room()).isNull();
        assertThatThrownBy(() -> rooms.detail(convo.id(), ana)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rooms.addMembers(convo.id(), ana, List.of("someone"))).isInstanceOf(ApiException.class);
    }
}
