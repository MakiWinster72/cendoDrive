package com.cendodrive.auth;

import com.cendodrive.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;

    @Test void registrationLoginAndLogoutWithDatabaseAndRedis() throws Exception {
        String username = "it_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String registration = mapper.writeValueAsString(new AuthDtos.RegisterRequest(username, "password123", null));
            String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            JsonNode created = mapper.readTree(response);
            assertTrue(created.get("id").isTextual());
            assertTrue(created.get("createdAt").asText().endsWith("Z"));
            String login = mapper.writeValueAsString(new AuthDtos.LoginRequest(username, "password123"));
            String token = mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
            mvc.perform(get("/api/user/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username));
            mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/user/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        } finally {
            users.findByUsername(username).ifPresent(users::delete);
        }
    }

    @Test void validationAndLoginRateLimit() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"!\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields.username").exists());
        String username = "it_missing_" + UUID.randomUUID().toString().replace("-", "");
        String login = mapper.writeValueAsString(new AuthDtos.LoginRequest(username, "password123"));
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
