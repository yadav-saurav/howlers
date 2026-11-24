package app.howlers.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP helpers shared by the account security integration tests. */
final class AuthHttp {
    static final String ORIGIN = "http://localhost:5173";
    static final String FIREFOX_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0";
    static final String CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    static final String EDGE_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    record Session(String username, String email, String token, Cookie refresh, Cookie device, JsonNode body) {
        String bearer() { return "Bearer " + token; }
    }

    private final MockMvc mvc;
    private final ObjectMapper json;

    AuthHttp(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    Session register(String name, String userAgent) throws Exception {
        String email = name + "@sec.test";
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", userAgent)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Session(name, email, body.get("accessToken").asText(), r.getResponse().getCookie("howlers_rt"),
                r.getResponse().getCookie("howlers_did"), body);
    }

    /** Password login; {@code device} may be null (a browser with no device cookie yet). */
    MvcResult loginRaw(String login, String password, Cookie device, String userAgent) throws Exception {
        var req = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("User-Agent", userAgent)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}");
        if (device != null) req.cookie(device);
        return mvc.perform(req).andReturn();
    }

    Session login(String login, Cookie device, String userAgent) throws Exception {
        MvcResult r = loginRaw(login, "password1", device, userAgent);
        if (r.getResponse().getStatus() != 200) throw new AssertionError("login " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Session(login, null, body.path("accessToken").asText(null), r.getResponse().getCookie("howlers_rt"),
                r.getResponse().getCookie("howlers_did"), body);
    }

    JsonNode read(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.createObjectNode() : json.readTree(s);
    }
}
