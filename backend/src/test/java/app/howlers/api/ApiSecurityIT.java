package app.howlers.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import app.howlers.support.TestIds;

/** End-to-end HTTP behaviour of authentication and server-side authorisation. */
@AutoConfigureMockMvc
class ApiSecurityIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Session(String token, Cookie refresh) {}

    private Session register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@t.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return new Session(json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText(),
                r.getResponse().getCookie("howlers_rt"));
    }

    private static String sfx() {
        return TestIds.suffix();
    }

    @Test
    void refreshTokenRotationDetectsReuse() throws Exception {
        Session s = register("rot" + sfx());
        assertThat(s.refresh().isHttpOnly()).isTrue();
        MvcResult first = mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh())).andExpect(status().isOk()).andReturn();
        Cookie rotated = first.getResponse().getCookie("howlers_rt");
        assertThat(rotated.getValue()).isNotEqualTo(s.refresh().getValue());
        // replaying the old token revokes the whole session family, including the new token
        mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsForeignOrigin() throws Exception {
        Session s = register("orig" + sfx());
        mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpointsRequireAuthAndInvalidTokensGet401() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/feed").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/feed")).andExpect(status().isOk());
    }

    @Test
    void privateCommunityContentIsHiddenFromNonMembers() throws Exception {
        String id = sfx();
        Session owner = register("own" + id);
        Session outsider = register("out" + id);
        String slug = "priv" + id;
        mvc.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + slug + "\",\"name\":\"Private\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isCreated());
        MvcResult created = mvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"TEXT\",\"title\":\"secret plans\"}"))
                .andExpect(status().isCreated()).andReturn();
        long postId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + outsider.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/communities/" + slug + "/posts").header("Authorization", "Bearer " + outsider.token())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/communities/" + slug + "/membership").header("Authorization", "Bearer " + outsider.token())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/posts/" + postId + "/vote").header("Authorization", "Bearer " + outsider.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":1}")).andExpect(status().isForbidden());

        MvcResult search = mvc.perform(get("/api/v1/search?q=secret").header("Authorization", "Bearer " + outsider.token()))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = json.readTree(search.getResponse().getContentAsString()).get("items");
        assertThat(items.findValuesAsText("id")).doesNotContain(Long.toString(postId));

        MvcResult ownerSearch = mvc.perform(get("/api/v1/search?q=secret").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk()).andReturn();
        assertThat(ownerSearch.getResponse().getContentAsString()).contains("secret plans");
    }

    @Test
    void onlyModeratorsManageCommunityAndRolesCannotEscalate() throws Exception {
        String id = sfx();
        Session owner = register("mo" + id);
        Session member = register("mm" + id);
        String slug = "mod" + id;
        mvc.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Mods\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/communities/" + slug + "/membership").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/communities/" + slug + "/rules").header("Authorization", "Bearer " + member.token())
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/communities/" + slug + "/members/mm" + id + "/role").header("Authorization", "Bearer " + member.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/communities/" + slug + "/members/mm" + id + "/role").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/communities/" + slug + "/rules").header("Authorization", "Bearer " + member.token())
                .contentType(MediaType.APPLICATION_JSON).content("[{\"title\":\"Be kind\"}]")).andExpect(status().isOk());
        // a moderator still cannot change the owner
        mvc.perform(put("/api/v1/communities/" + slug + "/members/mo" + id + "/role").header("Authorization", "Bearer " + member.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}")).andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsUseTheStandardShape() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"nope\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }
}
