package uz.biolife.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.biolife.identity.application.AuthService;
import uz.biolife.shared.security.CurrentUser.Role;
import uz.biolife.support.TestDatabase;

/** Login issues a JWT; the manager panel is reachable only with a manager token. */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityFlowTests {
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    final UUID managerId = UUID.randomUUID(), agentId = UUID.randomUUID();

    @BeforeAll
    void users() {
        auth.createUser(managerId, "boss", "boss-secret-1", "Abbos", Role.MANAGER);
        auth.createUser(agentId, "sardor.t", "agent-secret-1", "Sardor T.", Role.AGENT);
        auth.createUser(UUID.randomUUID(), "lock.me", "lock-secret-1", "Lock", Role.MANAGER);
        auth.createUser(UUID.randomUUID(), "pass.change", "old-secret-1", "Parol", Role.MANAGER);
    }

    private org.springframework.test.web.servlet.ResultActions login(String user, String pass) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"));
    }

    @Test
    void fiveWrongPasswordsLockTheLoginFor15Minutes() throws Exception {
        for (int i = 0; i < 5; i++) login("lock.me", "wrong-" + i).andExpect(status().isUnauthorized());
        login("lock.me", "lock-secret-1").andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("daqiqadan keyin")));
    }

    @Test
    void ownPasswordChangeNeedsTheCurrentOne() throws Exception {
        String t = token("pass.change", "old-secret-1");
        mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"nope\",\"newPassword\":\"new-secret-1\"}"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old-secret-1\",\"newPassword\":\"new-secret-1\"}"))
            .andExpect(status().isOk());
        login("pass.change", "old-secret-1").andExpect(status().isUnauthorized());
        login("pass.change", "new-secret-1").andExpect(status().isOk());
    }

    @Test
    void managerGivesAFieldAgentALoginWithTheAgentsOwnId() throws Exception {
        String boss = token("boss", "boss-secret-1");
        UUID fieldAgent = UUID.randomUUID();
        mvc.perform(post("/api/v1/panel/users/agents").header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + fieldAgent + "\",\"name\":\"Akmal Agent\",\"username\":\"akmal.agent\",\"password\":\"akmal-secret-1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fieldAgent.toString())).andExpect(jsonPath("$.role").value("AGENT"));
        mvc.perform(post("/api/v1/panel/users/agents").header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + fieldAgent + "\",\"name\":\"Akmal\",\"username\":\"akmal.two\",\"password\":\"akmal-secret-1\"}"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/panel/users").param("role", "AGENT").header("Authorization", "Bearer " + boss))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.username == 'akmal.agent')].id").value(fieldAgent.toString()));
        mvc.perform(get("/api/v1/panel/users").param("role", "ADMIN").header("Authorization", "Bearer " + boss)).andExpect(status().isUnprocessableContent());
        String agentToken = token("akmal.agent", "akmal-secret-1");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + agentToken))
            .andExpect(jsonPath("$.id").value(fieldAgent.toString())).andExpect(jsonPath("$.role").value("AGENT"));
    }

    @Test
    void managerCreatesAndDisablesAnotherManager() throws Exception {
        String boss = token("boss", "boss-secret-1");
        String created = mvc.perform(post("/api/v1/panel/users").header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"Filial.Rahbar\",\"name\":\"Filial rahbari\",\"password\":\"filial-secret\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("filial.rahbar")).andExpect(jsonPath("$.role").value("MANAGER"))
            .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String filial = token("filial.rahbar", "filial-secret");
        mvc.perform(get("/api/v1/panel/users").header("Authorization", "Bearer " + boss))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.username == 'filial.rahbar')].active").value(true));
        mvc.perform(patch("/api/v1/panel/users/{id}", id).header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // the old token stops working at once, and a new login is refused
        mvc.perform(get("/api/v1/panel/analytics/overview").header("Authorization", "Bearer " + filial)).andExpect(status().isUnauthorized());
        login("filial.rahbar", "filial-secret").andExpect(status().isUnauthorized());
        // nobody disables their own account; agents do not see the list
        mvc.perform(patch("/api/v1/panel/users/{id}", managerId).header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/panel/users").header("Authorization", "Bearer " + token("sardor.t", "agent-secret-1"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/panel/users").header("Authorization", "Bearer " + boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"name\":\"X\",\"password\":\"short\"}"))
            .andExpect(status().isUnprocessableContent());
    }

    private String token(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString())
            .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void wrongPasswordAndUnknownUserGiveSame401() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"boss\",\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Login yoki parol noto'g'ri")));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ghost\",\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Login yoki parol noto'g'ri")));
    }

    @Test
    void managerTokenOpensPanelAgentTokenDoesNot() throws Exception {
        String manager = token("BOSS", "boss-secret-1");
        String agent = token("sardor.t", "agent-secret-1");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + manager))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER")).andExpect(jsonPath("$.id").value(managerId.toString()));
        mvc.perform(get("/api/v1/panel/analytics/overview").header("Authorization", "Bearer " + manager)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/panel/rating/attention").header("Authorization", "Bearer " + manager)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/panel/analytics/overview").header("Authorization", "Bearer " + agent)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rating/leaderboard").header("Authorization", "Bearer " + agent)).andExpect(status().isOk());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String manager = token("boss", "boss-secret-1");
        String tampered = manager.substring(0, manager.length() - 2) + "xx";
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
