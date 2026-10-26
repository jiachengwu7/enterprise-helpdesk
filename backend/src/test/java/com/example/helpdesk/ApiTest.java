package com.example.helpdesk;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:helpdesk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true"
    })
@AutoConfigureMockMvc(
    printOnlyOnFailure = false,
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class ApiTest {
  static final String PASSWORD = UUID.randomUUID().toString();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    for (String name : List.of("admin", "agent", "employee", "hr"))
      r.add("app.seed." + name, () -> PASSWORD);
    r.add("app.ai.token", () -> UUID.randomUUID().toString());
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void anonymousCannotReadTickets() throws Exception {
    mvc.perform(get("/api/tickets"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  String login(String name) throws Exception {
    return data(mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(Map.of("username", name, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString())
        .path("token")
        .asText();
  }

  JsonNode data(String body) {
    return json.readTree(body).path("data");
  }

  JsonNode create(String token, String key) throws Exception {
    return data(
        mvc.perform(
                post("/api/tickets")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "title",
                                "Network access",
                                "description",
                                "Cannot connect to VPN",
                                "priority",
                                "HIGH",
                                "idempotencyKey",
                                key))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void employeeCannotReadOtherDepartmentsOrAssignAndDuplicateCreatesOneTicket() throws Exception {
    String emp = login("employee"), hr = login("hr");
    String key = UUID.randomUUID().toString();
    JsonNode ticket = create(emp, key);
    String id = ticket.path("id").asText();
    assertThat(create(emp, key).path("id").asText()).isEqualTo(id);
    mvc.perform(get("/api/tickets/" + id).header("Authorization", "Bearer " + hr))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/users").header("Authorization", "Bearer " + emp))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + emp)
                .contentType("application/json")
                .content("{\"status\":\"IN_PROGRESS\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void lifecycleRequiresResolutionThenCreatorRating() throws Exception {
    String emp = login("employee"), staff = login("agent");
    String id = create(emp, UUID.randomUUID().toString()).path("id").asText();
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + staff)
                .contentType("application/json")
                .content("{\"status\":\"RESOLVED\",\"resolution\":\"Fixed\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + staff)
                .contentType("application/json")
                .content("{\"status\":\"IN_PROGRESS\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + staff)
                .contentType("application/json")
                .content("{\"status\":\"RESOLVED\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + staff)
                .contentType("application/json")
                .content("{\"status\":\"RESOLVED\",\"resolution\":\"VPN configuration repaired\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            patch("/api/tickets/" + id)
                .header("Authorization", "Bearer " + emp)
                .contentType("application/json")
                .content("{\"status\":\"CLOSED\",\"rating\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CLOSED"));
  }

  @Test
  void conversationsAreOwnerOnlyAndLogoutRevokesToken() throws Exception {
    String emp = login("employee"), admin = login("admin");
    String id =
        data(mvc.perform(
                    post("/api/conversations")
                        .header("Authorization", "Bearer " + emp)
                        .contentType("application/json")
                        .content("{\"title\":\"Private\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    mvc.perform(get("/api/conversations/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + emp))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + emp))
        .andExpect(status().isUnauthorized());
  }
}
