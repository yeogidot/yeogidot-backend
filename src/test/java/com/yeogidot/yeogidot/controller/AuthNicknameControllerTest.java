package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.config.SecurityConfig;
import com.yeogidot.yeogidot.dto.MyProfileResponse;
import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.*;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import com.yeogidot.yeogidot.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
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

/** 실제 SecurityConfig와 JWT 필터를 통과시키되 DB/Redis는 mock으로 격리한다. */
@SpringJUnitConfig(AuthNicknameControllerTest.Config.class)
@org.springframework.test.context.TestPropertySource(properties = {"jwt.expiration=3600000", "jwt.secret=test-only-unused-secret"})
@WebAppConfiguration
class AuthNicknameControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired StringRedisTemplate redis;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(auth, users, tokens, redis);
        mvc = webAppContextSetup(context)
                .addFilter(new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true))
                .apply(springSecurity()).build();
    }

    private void loginFixture() {
        User user = User.builder().id(7L).email("me@example.com").password("secret")
                .passwordChangedAt(Instant.EPOCH).build();
        when(users.findByEmail("me@example.com")).thenReturn(Optional.of(user));
        when(tokens.validateToken("valid-token")).thenReturn(true);
        when(tokens.getEmail("valid-token")).thenReturn("me@example.com");
        when(tokens.getIssuedAt("valid-token")).thenReturn(Instant.now());
        when(tokens.getAuthentication("valid-token")).thenReturn(
                new UsernamePasswordAuthenticationToken("me@example.com", null, List.of()));
    }

    @Test
    void anonymousRequestsCannotReadOrChangeProfile() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/auth/nickname").contentType("application/json")
                .content("{\"nickname\":\"여행자\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, users);
    }

    @Test
    void profileShowsUnsetNicknameWithoutExposingPassword() throws Exception {
        loginFixture();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.nicknameRequired").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.nicknameKey").doesNotExist());
    }

    @Test
    void patchUsesAuthenticatedAccountEvenIfBodyContainsAnotherId() throws Exception {
        loginFixture();
        when(auth.changeNickname(7L, "여행자")).thenReturn(
                new MyProfileResponse(7L, "me@example.com", "여행자", false));
        mvc.perform(patch("/api/auth/nickname").header("Authorization", "Bearer valid-token")
                .contentType("application/json").content("{\"nickname\":\"여행자\",\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("여행자"))
                .andExpect(jsonPath("$.nicknameRequired").value(false));
        verify(auth).changeNickname(7L, "여행자");
    }

    @Test
    void duplicateNicknameReturns409() throws Exception {
        loginFixture();
        when(auth.changeNickname(7L, "Taken")).thenThrow(new DuplicateNicknameException());
        mvc.perform(patch("/api/auth/nickname").header("Authorization", "Bearer valid-token")
                .contentType("application/json").content("{\"nickname\":\"Taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NICKNAME_ALREADY_EXISTS"));
    }

    @Test
    void duplicateSignupReturns409() throws Exception {
        doThrow(new DuplicateNicknameException()).when(auth).signup(any(SignupRequest.class), anyString());
        mvc.perform(post("/api/auth/signup").contentType("application/json")
                .content("{\"email\":\"new@example.com\",\"nickname\":\"Taken\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidNicknameReturns400() throws Exception {
        loginFixture();
        when(auth.changeNickname(7L, " ")).thenThrow(new IllegalArgumentException("닉네임 형식 오류"));
        mvc.perform(patch("/api/auth/nickname").header("Authorization", "Bearer valid-token")
                .contentType("application/json").content("{\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTokenIsRejectedByFilter() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verify(tokens).validateToken("invalid-token");
        verifyNoInteractions(auth, users);
    }

    @Test
    void signupWithoutNicknameKeepsExistingResponse() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType("application/json")
                .content("{\"email\":\"old@example.com\"}"))
                .andExpect(status().isCreated()).andExpect(content().string("회원가입 성공"));
        verify(auth).signup(argThat(request -> request.getNickname() == null), anyString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AuthController.class, SecurityConfig.class, GlobalExceptionHandler.class,
            JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
    static class Config implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            // 수동 MVC 컨텍스트에도 Spring Boot의 UTF-8 문자열 응답 기본값을 적용한다.
            converters.stream().filter(org.springframework.http.converter.StringHttpMessageConverter.class::isInstance)
                    .map(org.springframework.http.converter.StringHttpMessageConverter.class::cast)
                    .forEach(converter -> converter.setDefaultCharset(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean JwtTokenProvider jwtTokenProvider() { return mock(JwtTokenProvider.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
    }
}
