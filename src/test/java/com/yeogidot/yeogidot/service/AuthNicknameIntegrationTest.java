package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.DuplicateNicknameException;
import com.yeogidot.yeogidot.repository.*;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 실제 DB 제약과 트랜잭션은 H2로 검증하며 운영 설정과 외부 서비스는 로드하지 않는다. */
@SpringJUnitConfig(AuthNicknameIntegrationTest.Config.class)
@org.springframework.test.context.TestPropertySource(properties = {"jwt.expiration=3600000", "jwt.secret=test-only-unused-secret"})
class AuthNicknameIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void resetData() {
        users.deleteAll();
        reset(encoder);
        when(encoder.encode(anyString())).thenReturn("encoded-password");
    }

    @Test
    void signupSavesDisplayAndNormalizedKey() {
        auth.signup(signup("new@example.com", "  여행_Jinwon  "), null);
        User stored = users.findByEmail("new@example.com").orElseThrow();
        assertThat(stored.getNickname()).isEqualTo("여행_Jinwon");
        assertThat(stored.getNicknameKey()).isEqualTo("여행_Jinwon");
        assertThat(stored.getPassword()).isEqualTo("encoded-password");
    }

    @Test
    void legacySignupsCanRemainUnsetAndSetNicknameLater() {
        auth.signup(signup("old1@example.com", null), null);
        auth.signup(signup("old2@example.com", null), null);
        User first = users.findByEmail("old1@example.com").orElseThrow();
        assertThat(first.getNickname()).isNull();
        var profile = auth.changeNickname(first.getId(), "여행자");
        assertThat(profile.nicknameRequired()).isFalse();
        assertThat(profile.nickname()).isEqualTo("여행자");
        assertThat(users.findByEmail("old2@example.com").orElseThrow().getNickname()).isNull();
    }

    @Test
    void duplicateSignupIgnoresOnlyOuterWhitespace() {
        auth.signup(signup("first@example.com", "Jinwon"), null);
        assertThatThrownBy(() -> auth.signup(signup("second@example.com", " Jinwon "), null))
                .isInstanceOf(DuplicateNicknameException.class);
        assertThat(users.findByEmail("second@example.com")).isEmpty();
    }

    @Test
    void differentUsersCanRegisterNamesThatDifferOnlyInCase() {
        auth.signup(signup("first@example.com", "Jinwon"), null);
        auth.signup(signup("second@example.com", "jInwon"), null);
        assertThat(users.count()).isEqualTo(2);
        assertThat(users.findByEmail("first@example.com").orElseThrow().getNicknameKey()).isEqualTo("Jinwon");
        assertThat(users.findByEmail("second@example.com").orElseThrow().getNicknameKey()).isEqualTo("jInwon");
    }

    @Test
    void renameAllowsDifferentCaseButRejectsExactNameOfAnotherUser() {
        auth.signup(signup("first@example.com", "Jinwon"), null);
        auth.signup(signup("second@example.com", "Other"), null);
        Long secondId = users.findByEmail("second@example.com").orElseThrow().getId();
        assertThat(auth.changeNickname(secondId, "jInwon").nickname()).isEqualTo("jInwon");
        assertThatThrownBy(() -> auth.changeNickname(secondId, "Jinwon"))
                .isInstanceOf(DuplicateNicknameException.class);
        assertThat(users.findById(secondId).orElseThrow().getNickname()).isEqualTo("jInwon");
    }

    @Test
    void renameRejectsAnotherUsersNameAndLeavesOldNameUnchanged() {
        auth.signup(signup("first@example.com", "Jinwon"), null);
        auth.signup(signup("second@example.com", "Other"), null);
        User second = users.findByEmail("second@example.com").orElseThrow();
        assertThatThrownBy(() -> auth.changeNickname(second.getId(), "Jinwon"))
                .isInstanceOf(DuplicateNicknameException.class);
        assertThat(users.findById(second.getId()).orElseThrow().getNickname()).isEqualTo("Other");
    }

    @Test
    void sameUserCanKeepNameOrChangeItsCaseAndReleaseOldName() {
        auth.signup(signup("first@example.com", "Jinwon"), null);
        Long id = users.findByEmail("first@example.com").orElseThrow().getId();
        auth.changeNickname(id, "Jinwon");
        auth.changeNickname(id, "JINWON");
        assertThat(users.findById(id).orElseThrow().getNickname()).isEqualTo("JINWON");
        auth.changeNickname(id, "NewName");
        auth.signup(signup("second@example.com", "Jinwon"), null);
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void blankNicknameIsRejectedInsteadOfTreatedAsLegacySignup() {
        assertThatThrownBy(() -> auth.signup(signup("blank@example.com", "  "), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(users.count()).isZero();
    }

    @Test
    void entityBuilderAlsoNormalizesNicknameBeforePersistence() {
        User stored = users.saveAndFlush(User.builder().email("builder@example.com")
                .password("encoded").nickname("  Builder  ").nicknameKey("wrong-key").build());
        assertThat(stored.getNickname()).isEqualTo("Builder");
        assertThat(stored.getNicknameKey()).isEqualTo("Builder");
        assertThatThrownBy(() -> auth.signup(signup("other@example.com", "Builder"), null))
                .isInstanceOf(DuplicateNicknameException.class);
    }

    @Test
    void concurrentSignupAfterBothPrechecksAllowsOnlyOneAndReturnsDomainConflict() throws Exception {
        var afterPrecheck = new CyclicBarrier(2);
        // encode는 중복 사전 조회 다음에 호출된다. 두 요청 모두 조회를 통과시킨다.
        when(encoder.encode(anyString())).thenAnswer(invocation -> {
            afterPrecheck.await(10, TimeUnit.SECONDS);
            return "encoded";
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> first = () -> trySignup("race1@example.com", "SameName");
            Callable<String> second = () -> trySignup("race2@example.com", "SameName");
            var results = pool.invokeAll(List.of(first, second), 20, TimeUnit.SECONDS);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder("created", "duplicate");
        }
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.existsByNicknameKey("SameName")).isTrue();
    }

    private String trySignup(String email, String nickname) {
        try {
            auth.signup(signup(email, nickname), null);
            return "created";
        } catch (DuplicateNicknameException expected) {
            return "duplicate";
        }
    }

    static SignupRequest signup(String email, String nickname) {
        var request = new SignupRequest();
        request.setEmail(email);
        request.setNickname(nickname);
        request.setPassword("password123");
        request.setPassword_check("password123");
        request.setPrivacy_policy_agreed(true);
        return request;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import(AuthService.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:nickname-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean PasswordEncoder passwordEncoder() { return mock(PasswordEncoder.class); }
        @Bean JwtTokenProvider jwtTokenProvider() { return mock(JwtTokenProvider.class); }
        @Bean LoginAttemptService loginAttemptService() { return mock(LoginAttemptService.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean GcsService gcsService() { return mock(GcsService.class); }
    }
}
