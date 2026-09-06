package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.dto.DeleteAccountRequest;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.repository.TravelLogRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 삭제 기능의 DB 트랜잭션과 R2 파일 삭제 순서를 검증한다.
 *
 * <p>운영 코드 수정 전에는 8개 테스트가 모두 실패하는 것이 기준선이다.
 * 운영 코드 수정 후에는 같은 테스트가 모두 통과해야 한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeletionR2ConsistencyTest {

    private static final Long USER_ID = 1L;
    private static final Long TRAVEL_ID = 100L;
    private static final Long DAY_ID = 10L;
    private static final Long PHOTO_ID = 1000L;
    private static final String PHOTO_URL = "https://cdn.example.com/deletion-target.jpg";
    private static final String RAW_PASSWORD = "plain-password";
    private static final String ENCODED_PASSWORD = "encoded-password";

    @Mock
    private TravelRepository travelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PhotoRepository photoRepository;
    @Mock
    private TravelDayRepository travelDayRepository;
    @Mock
    private TravelLogRepository travelLogRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private GcsService gcsService;
    @Mock
    private GeoCodingService geoCodingService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private StringRedisTemplate redisTemplate;

    private TravelService travelService;
    private PhotoService photoService;
    private AuthService authService;
    private User owner;
    private Travel travel;
    private TravelDay day;
    private Photo assignedPhoto;
    private Photo unassignedPhoto;

    @BeforeEach
    void setUp() {
        travelService = new TravelService(
                travelRepository,
                userRepository,
                photoRepository,
                travelDayRepository,
                travelLogRepository,
                gcsService,
                geoCodingService,
                new OwnershipValidator()
        );

        photoService = new PhotoService(
                photoRepository,
                commentRepository,
                gcsService,
                geoCodingService,
                objectMapper,
                travelDayRepository,
                new OwnershipValidator()
        );

        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtTokenProvider,
                loginAttemptService,
                redisTemplate,
                photoRepository,
                travelRepository,
                commentRepository,
                gcsService
        );

        owner = User.builder()
                .id(USER_ID)
                .email("owner@example.com")
                .password(ENCODED_PASSWORD)
                .build();

        travel = Travel.builder()
                .id(TRAVEL_ID)
                .user(owner)
                .title("삭제 순서 테스트 여행")
                .startDate(LocalDate.of(2026, 7, 29))
                .endDate(LocalDate.of(2026, 7, 29))
                .build();

        day = TravelDay.builder()
                .id(DAY_ID)
                .travel(travel)
                .dayNumber(1)
                .date(LocalDate.of(2026, 7, 29))
                .build();
        travel.addDay(day);

        assignedPhoto = photo(PHOTO_ID, day);
        unassignedPhoto = photo(PHOTO_ID, null);

        when(travelRepository.findById(TRAVEL_ID)).thenReturn(Optional.of(travel));
        when(travelDayRepository.findById(DAY_ID)).thenReturn(Optional.of(day));
        when(travelDayRepository.findByTravelId(TRAVEL_ID)).thenReturn(List.of(day));
        when(photoRepository.findByTravelDayIn(List.of(day))).thenReturn(List.of(assignedPhoto));
        when(photoRepository.findByTravelDay(day)).thenReturn(List.of(assignedPhoto));
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(unassignedPhoto));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
        when(photoRepository.findByUserId(USER_ID)).thenReturn(List.of(assignedPhoto));
        when(travelRepository.findAllByUserOrderByIdDesc(owner)).thenReturn(List.of(travel));
        when(photoRepository.findByUserIdAndTravelDayIsNull(USER_ID)).thenReturn(List.of(unassignedPhoto));
    }

    @Test
    void 여행_전체삭제는_DB_커밋실패시_R2파일을_삭제하지_않는다() {
        assertR2NotDeletedWhenCommitFails(() -> travelService.deleteTravel(TRAVEL_ID, owner));
    }

    @Test
    void 여행_전체삭제는_DB_커밋후_R2파일을_삭제한다() throws InterruptedException {
        assertR2DeletedAfterCommit(() -> travelService.deleteTravel(TRAVEL_ID, owner));
    }

    @Test
    void 여행_일차삭제는_DB_커밋실패시_R2파일을_삭제하지_않는다() {
        assertR2NotDeletedWhenCommitFails(() -> travelService.deleteTravelDay(DAY_ID, owner));
    }

    @Test
    void 여행_일차삭제는_DB_커밋후_R2파일을_삭제한다() throws InterruptedException {
        assertR2DeletedAfterCommit(() -> travelService.deleteTravelDay(DAY_ID, owner));
    }

    @Test
    void 단일_사진삭제는_DB_커밋실패시_R2파일을_삭제하지_않는다() {
        assertR2NotDeletedWhenCommitFails(() -> photoService.deletePhoto(PHOTO_ID, USER_ID));
    }

    @Test
    void 단일_사진삭제는_DB_커밋후_R2파일을_삭제한다() throws InterruptedException {
        assertR2DeletedAfterCommit(() -> photoService.deletePhoto(PHOTO_ID, USER_ID));
    }

    @Test
    void 회원탈퇴는_DB_커밋실패시_R2파일을_삭제하지_않는다() {
        DeleteAccountRequest request = deleteAccountRequest();

        assertR2NotDeletedWhenCommitFails(() -> authService.deleteAccount(USER_ID, request, null));
    }

    @Test
    void 회원탈퇴는_DB_커밋후_R2파일을_삭제한다() throws InterruptedException {
        DeleteAccountRequest request = deleteAccountRequest();

        assertR2DeletedAfterCommit(() -> authService.deleteAccount(USER_ID, request, null));
    }

    private void assertR2NotDeletedWhenCommitFails(Runnable operation) {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(true);

        assertThrows(
                RuntimeException.class,
                () -> executeInTransaction(transactionManager, operation)
        );

        verify(gcsService, never()).deleteFile(PHOTO_URL);
    }

    private void assertR2DeletedAfterCommit(Runnable operation) throws InterruptedException {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(false);
        AtomicBoolean deletedAfterCommit = new AtomicBoolean(false);
        CountDownLatch deleteCalled = new CountDownLatch(1);

        doAnswer(invocation -> {
            deletedAfterCommit.set(transactionManager.isCommitted());
            deleteCalled.countDown();
            return null;
        }).when(gcsService).deleteFile(PHOTO_URL);

        executeInTransaction(transactionManager, operation);

        assertTrue(deleteCalled.await(1, TimeUnit.SECONDS), "R2 파일 삭제가 호출되어야 한다.");
        assertTrue(deletedAfterCommit.get(), "R2 파일 삭제는 DB 커밋이 성공한 뒤에 실행되어야 한다.");
    }

    private void executeInTransaction(RecordingTransactionManager transactionManager, Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operation.run());
    }

    private DeleteAccountRequest deleteAccountRequest() {
        DeleteAccountRequest request = new DeleteAccountRequest();
        ReflectionTestUtils.setField(request, "password", RAW_PASSWORD);
        return request;
    }

    private Photo photo(Long id, TravelDay travelDay) {
        return Photo.builder()
                .id(id)
                .user(owner)
                .travelDay(travelDay)
                .filePath(PHOTO_URL)
                .originalName("deletion-target.jpg")
                .takenAt(LocalDateTime.of(2026, 7, 29, 12, 0))
                .build();
    }

    /**
     * 실제 DB 없이 커밋 성공/실패와 TransactionSynchronization 순서를 재현한다.
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final boolean failOnCommit;
        private final AtomicBoolean committed = new AtomicBoolean(false);

        private RecordingTransactionManager(boolean failOnCommit) {
            this.failOnCommit = failOnCommit;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            committed.set(false);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failOnCommit) {
                throw new IllegalStateException("강제로 발생시킨 DB 커밋 실패");
            }
            committed.set(true);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            committed.set(false);
        }

        boolean isCommitted() {
            return committed.get();
        }
    }
}
