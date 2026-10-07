package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.config.SecurityConfig;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.*;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import com.yeogidot.yeogidot.service.PhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** 실제 SecurityConfig/JWT 필터/MVC를 사용하며 DB와 Redis는 mock으로 격리한다. */
@SpringJUnitConfig(PhotoCommentControllerTest.Config.class)
@TestPropertySource(properties = {"jwt.expiration=3600000", "jwt.secret=test-only-unused-secret"})
@WebAppConfiguration
class PhotoCommentControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired PhotoService photos;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired StringRedisTemplate redis;
    private MockMvc mvc;
    private User writer;

    @BeforeEach
    void setUp() {
        reset(photos, users, tokens, redis);
        writer = User.builder().id(7L).email("writer@example.test").password("test-only")
                .nickname("Writer").passwordChangedAt(Instant.EPOCH).build();
        mvc = webAppContextSetup(context)
                .addFilter(new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true))
                .apply(springSecurity()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "LEGACY_PUT", "LEGACY_DELETE"})
    void anonymousRequestsCannotWriteModifyOrDeleteComments(String method) throws Exception {
        mvc.perform(request(method)).andExpect(status().isUnauthorized());
        verifyNoInteractions(photos, users);
    }

    @Test
    void registrationKeepsLegacyIdAndAddsCommentIdUsingTheAuthenticatedWriter() throws Exception {
        login();
        when(photos.createComment(eq(10L), any(), same(writer))).thenReturn(42L);
        mvc.perform(post("/api/photos/10/comments").header("Authorization", "Bearer valid-token")
                .contentType("application/json").content("{\"content\":\"hello\",\"writerId\":99}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cmentId").value(42))
                .andExpect(jsonPath("$.commentId").value(42));
        verify(photos).createComment(eq(10L), argThat(r -> r.getContent().equals("hello")), same(writer));
    }

    @Test
    void newUpdatePassesBothIdsAndKeepsTheExistingSuccessStatus() throws Exception {
        login();
        mvc.perform(request("PUT").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        verify(photos).updateComment(eq(10L), eq(42L), argThat(r -> r.getContent().equals("hello")), same(writer));
        verify(photos, never()).updateCommentByPhotoId(anyLong(), any(), any());
    }

    @Test
    void newDeletePassesBothIdsAndReturns204() throws Exception {
        login();
        mvc.perform(request("DELETE").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
        verify(photos).deleteComment(10L, 42L, writer);
        verify(photos, never()).deleteCommentByPhotoId(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void absentCommentOrWrongPhotoReturns404(String method) throws Exception {
        login();
        if (method.equals("PUT")) {
            doThrow(new ResourceNotFoundException("댓글", 42L)).when(photos)
                    .updateComment(eq(10L), eq(42L), any(), same(writer));
        } else {
            doThrow(new ResourceNotFoundException("댓글", 42L)).when(photos).deleteComment(10L, 42L, writer);
        }
        mvc.perform(request(method).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void servicePermissionFailureReturns403(String method) throws Exception {
        login();
        if (method.equals("PUT")) {
            doThrow(new SecurityException("본인의 댓글만 수정할 수 있습니다.")).when(photos)
                    .updateComment(eq(10L), eq(42L), any(), same(writer));
        } else {
            doThrow(new SecurityException("댓글을 삭제할 권한이 없습니다.")).when(photos).deleteComment(10L, 42L, writer);
        }
        mvc.perform(request(method).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGACY_PUT", "LEGACY_DELETE"})
    void legacyMultipleCommentFailureReturnsExplicit409(String method) throws Exception {
        login();
        if (method.equals("LEGACY_PUT")) {
            doThrow(new CommentSelectionRequiredException()).when(photos)
                    .updateCommentByPhotoId(eq(10L), any(), same(writer));
        } else {
            doThrow(new CommentSelectionRequiredException()).when(photos).deleteCommentByPhotoId(10L, writer);
        }
        mvc.perform(request(method).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("COMMENT_SELECTION_REQUIRED"));
    }

    @Test
    void legacySuccessPathsRemainAvailable() throws Exception {
        login();
        mvc.perform(request("LEGACY_PUT").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
        mvc.perform(request("LEGACY_DELETE").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
        verify(photos).updateCommentByPhotoId(eq(10L), any(), same(writer));
        verify(photos).deleteCommentByPhotoId(10L, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\"   \"}"})
    void allWriteEndpointsRejectMissingOrBlankContent(String body) throws Exception {
        login();
        for (String method : List.of("POST", "PUT", "LEGACY_PUT")) {
            mvc.perform(request(method).header("Authorization", "Bearer valid-token").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(photos);
    }

    @Test
    void invalidTokenCannotDeleteAComment() throws Exception {
        mvc.perform(request("DELETE").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(photos);
    }

    private MockHttpServletRequestBuilder request(String method) {
        return switch (method) {
            case "POST" -> post("/api/photos/10/comments").contentType("application/json").content("{\"content\":\"hello\"}");
            case "PUT" -> put("/api/photos/10/comments/42").contentType("application/json").content("{\"content\":\"hello\"}");
            case "DELETE" -> delete("/api/photos/10/comments/42");
            case "LEGACY_PUT" -> put("/api/photos/10/comments").contentType("application/json").content("{\"content\":\"hello\"}");
            case "LEGACY_DELETE" -> delete("/api/photos/10/comments");
            default -> throw new IllegalArgumentException(method);
        };
    }

    private void login() {
        when(users.findByEmail(writer.getEmail())).thenReturn(Optional.of(writer));
        when(tokens.validateToken("valid-token")).thenReturn(true);
        when(tokens.getEmail("valid-token")).thenReturn(writer.getEmail());
        when(tokens.getIssuedAt("valid-token")).thenReturn(Instant.now());
        when(tokens.getAuthentication("valid-token")).thenReturn(
                new UsernamePasswordAuthenticationToken(writer.getEmail(), null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({PhotoController.class, SecurityConfig.class, GlobalExceptionHandler.class,
            JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
    static class Config {
        @Bean PhotoService photoService() { return mock(PhotoService.class); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean JwtTokenProvider jwtTokenProvider() { return mock(JwtTokenProvider.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
    }
}
