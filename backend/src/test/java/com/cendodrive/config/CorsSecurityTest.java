package com.cendodrive.config;

import com.cendodrive.auth.AuthController;
import com.cendodrive.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@TestPropertySource(properties = "app.cors.allowed-origins=https://app.example")
class CorsSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean AuthService auth;

    @Test void allowedOriginPreflightPassesSecurityFilter() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "https://app.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));
    }

    @Test void untrustedOriginPreflightIsRejected() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @Test void allowedOriginCanPreflightFileMutation() throws Exception {
        mvc.perform(options("/api/files/1/move").header("Origin", "https://app.example")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));
    }

    @Test void allowedOriginCanPreflightEmptyTrash() throws Exception {
        mvc.perform(options("/api/files/trash").header("Origin", "https://app.example")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));
    }
}
