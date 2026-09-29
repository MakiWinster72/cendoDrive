package com.cendodrive.file;

import com.cendodrive.auth.AuthService;
import com.cendodrive.config.CorsConfig;
import com.cendodrive.config.SecurityConfig;
import com.cendodrive.file.FileDtos.*;
import com.cendodrive.user.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class FileControllerTest {
    @Autowired MockMvc mvc;
    @MockBean FileService files;
    @MockBean AuthService auth;
    private RequestPostProcessor owner() {
        User user = new User("alice", "hash", "Alice");
        ReflectionTestUtils.setField(user, "id", 1L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @Test void anonymousRequestsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/files/folder").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"0\",\"name\":\"Docs\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(files);
    }
    @Test void usesAuthenticatedOwnerAndDefaults() throws Exception {
        when(files.list(1, 0, 0, 50)).thenReturn(new FileListResponse("0", List.of(), 0, 50, 0));
        mvc.perform(get("/api/files").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value("0")).andExpect(jsonPath("$.items").isArray());
        verify(files).list(1, 0, 0, 50);
    }
    @Test void rejectsMissingParentAndMalformedId() throws Exception {
        mvc.perform(post("/api/files/folder").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Docs\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(put("/api/files/not-a-number/rename").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Docs\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(put("/api/files/1/move").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetParentId\":\"-1\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(files);
    }
    @Test void returnsCreatedAndIgnoresClientOwnership() throws Exception {
        mvc.perform(post("/api/files/folder").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"0\",\"name\":\"Docs\",\"userId\":\"999\"}"))
                .andExpect(status().isCreated());
        verify(files).createFolder(1, 0, "Docs");
    }
    @Test void exposesStableBusinessErrorWithoutStorageDetails() throws Exception {
        when(files.rename(anyLong(), anyLong(), any())).thenThrow(FileBusinessException.notFound());
        mvc.perform(put("/api/files/99/rename").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Docs\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }
}
