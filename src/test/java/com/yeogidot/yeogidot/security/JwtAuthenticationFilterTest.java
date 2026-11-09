package com.yeogidot.yeogidot.security;

import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @Test
    void authenticatedRequestParsesTokenOnlyOnce() throws Exception {
        String email = "traveler@example.invalid";
        UserDetails principal = org.springframework.security.core.userdetails.User.withUsername(email)
                .password("unused")
                .roles("USER")
                .build();
        JwtTokenProvider provider = spy(new JwtTokenProvider(username -> principal));
        byte[] key = Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded();
        ReflectionTestUtils.setField(provider, "secretKey", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.setField(provider, "validityInMilliseconds", 60_000L);
        provider.init();
        String token = provider.createToken(1L, email);

        UserRepository repository = mock(UserRepository.class);
        User account = mock(User.class);
        when(account.getPasswordChangedAt()).thenReturn(Instant.EPOCH);
        when(repository.findByEmail(email)).thenReturn(Optional.of(account));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                provider, mock(StringRedisTemplate.class), repository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/travels");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        try {
            filter.doFilter(request, response, chain);

            verify(provider).parseClaims(token);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal);
            assertThat(chain.getRequest()).isSameAs(request);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
