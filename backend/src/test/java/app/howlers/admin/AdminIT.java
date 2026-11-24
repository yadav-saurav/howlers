package app.howlers.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import app.howlers.support.TestIds;

/** Admin dashboard over HTTP: role enforcement, suspension (login, refresh, live tokens), roles, quarantine, audit log. */
@AutoConfigureMockMvc
class AdminIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private record Session(long id, String username, String token, Cookie refresh) {}

    private Session register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@a.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Session(body.at("/user/id").asLong(), name, body.get("accessToken").asText(), r.getResponse().getCookie("howlers_rt"));
    }

    /** Admins are made with SQL, as an operator would; the request filter reads the role from the account. */
    private Session admin(String name) throws Exception {
        Session s = register(name);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(s.id()).update();
        return s;
    }

    private static String sfx() {
        return TestIds.suffix();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Session s) {
        return b.header("Authorization", "Bearer " + s.token());
    }

    private java.util.List<String> feedIds() throws Exception {
        JsonNode items = body(mvc.perform(get("/api/v1/feed?tab=latest&limit=50")).andExpect(status().isOk()).andReturn()).get("items");
        java.util.List<String> ids = new java.util.ArrayList<>();
        items.forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void adminEndpointsRequireTheAdminRole() throws Exception {
        String id = sfx();
        Session user = register("na" + id);
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/admin/overview"), user)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/users"), user)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/admin/users/" + user.id() + "/suspend"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"self\"}")).andExpect(status().isForbidden());

        Session admin = admin("ad" + id);
        MvcResult overview = mvc.perform(as(get("/api/v1/admin/overview"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signups.length()").value(14)).andReturn();
        long today = body(overview).get("signups").get(13).get("count").asLong();
        assertThat(today).isGreaterThanOrEqualTo(2); // at least the two accounts registered above
        assertThat(body(overview).get("users").asLong()).isGreaterThanOrEqualTo(2);

        // Demoting the admin takes effect on the very next request, without waiting for the token to expire.
        Session other = admin("ad2" + id);
        mvc.perform(as(post("/api/v1/admin/users/" + admin.id() + "/role"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\",\"reason\":\"rotation\"}")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/admin/overview"), admin)).andExpect(status().isForbidden());
        // ...and nobody can change their own rights
        mvc.perform(as(post("/api/v1/admin/users/" + other.id() + "/role"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void suspensionBlocksLoginRefreshAndLiveTokensAndIsAudited() throws Exception {
        String id = sfx();
        Session admin = admin("sa" + id);
        Session target = register("st" + id);
        mvc.perform(as(get("/api/v1/users/me"), target)).andExpect(status().isOk());

        mvc.perform(as(post("/api/v1/admin/users/" + target.id() + "/suspend"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Spam ring\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.user.suspendedReason").value("Spam ring"));

        mvc.perform(as(get("/api/v1/users/me"), target)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(target.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + target.username() + "\",\"password\":\"password1\"}")).andExpect(status().isForbidden());
        // admins can't be suspended while they are admins, and nobody can suspend themselves
        mvc.perform(as(post("/api/v1/admin/users/" + admin.id() + "/suspend"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isBadRequest());

        mvc.perform(as(post("/api/v1/admin/users/" + target.id() + "/unsuspend"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Appeal accepted\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + target.username() + "\",\"password\":\"password1\"}")).andExpect(status().isOk());

        MvcResult audit = mvc.perform(as(get("/api/v1/admin/audit?limit=100"), admin)).andExpect(status().isOk()).andReturn();
        JsonNode items = body(audit).get("items");
        boolean suspended = false, unsuspended = false;
        for (JsonNode a : items) {
            if (a.at("/targetUser/id").asLong() != target.id()) continue;
            if (a.get("action").asText().equals("SUSPEND_USER") && a.get("reason").asText().equals("Spam ring")
                    && a.at("/admin/id").asLong() == admin.id()) suspended = true;
            if (a.get("action").asText().equals("UNSUSPEND_USER")) unsuspended = true;
        }
        assertThat(suspended).isTrue();
        assertThat(unsuspended).isTrue();

        // The audit table is append-only at the database level.
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin_actions WHERE target_user_id = ?").param(target.id()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE admin_actions SET reason = 'edited' WHERE target_user_id = ?").param(target.id()).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void quarantineHidesCommunityFromPopularAndSearchAndBanClosesIt() throws Exception {
        String id = sfx();
        Session admin = admin("qa" + id);
        Session owner = register("qo" + id);
        String slug = "quar" + id;
        mvc.perform(as(post("/api/v1/communities"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Quarantine me\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        MvcResult created = mvc.perform(as(post("/api/v1/posts"), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"TEXT\",\"title\":\"zanzibarquokka sighting\"}"))
                .andExpect(status().isCreated()).andReturn();
        String postId = body(created).get("id").asText();

        assertThat(feedIds()).contains(postId);

        mvc.perform(as(post("/api/v1/admin/communities/" + slug + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"QUARANTINED\"}")).andExpect(status().isBadRequest()); // reason required
        mvc.perform(as(post("/api/v1/admin/communities/" + slug + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUARANTINED\",\"reason\":\"Harassment campaign\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUARANTINED"));

        assertThat(feedIds()).doesNotContain(postId);
        assertThat(mvc.perform(get("/api/v1/search?q=zanzibarquokka")).andReturn().getResponse().getContentAsString()).doesNotContain("zanzibarquokka sighting");
        // still reachable directly, with the status for the banner
        mvc.perform(get("/api/v1/communities/" + slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINED")).andExpect(jsonPath("$.statusReason").value("Harassment campaign"));
        mvc.perform(get("/api/v1/communities/" + slug + "/posts")).andExpect(status().isOk());

        mvc.perform(as(post("/api/v1/admin/communities/" + slug + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BANNED\",\"reason\":\"Repeated violations\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/communities/" + slug + "/posts")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/communities/" + slug + "/posts"), admin)).andExpect(status().isOk());

        // the moderators were told
        MvcResult inbox = mvc.perform(as(get("/api/v1/notifications"), owner)).andExpect(status().isOk()).andReturn();
        assertThat(inbox.getResponse().getContentAsString()).contains("was banned by the site admins");

        MvcResult audit = mvc.perform(as(get("/api/v1/admin/audit?limit=100"), admin)).andReturn();
        assertThat(audit.getResponse().getContentAsString()).contains("QUARANTINE_COMMUNITY").contains("BAN_COMMUNITY");
    }
}
