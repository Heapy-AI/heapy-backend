package com.heapy.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.HeapyBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest(classes = HeapyBackendApplication.class)
class OpenApiDocumentationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void OpenAPI_JSON에_로그인_요청과_응답_계약이_생성된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("HEAPY Backend API"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/terms'].get").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/users/me/profile'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/users/me/consents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/users/me/consents'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me/onboarding/complete'].post").exists())
                .andExpect(jsonPath("$.paths['/api/home'].get").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['503']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
    }
}
