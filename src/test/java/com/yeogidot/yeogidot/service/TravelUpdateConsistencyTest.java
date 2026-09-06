package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelUpdateRequest;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.repository.TravelLogRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 여행 수정의 DB/R2 일관성 회귀 테스트.
 *
 * <p>운영 코드 수정 전에는 일부 테스트가 실패하는 것이 정상이다. 실패 결과를 기준선으로
 * 기록한 뒤 운영 코드를 수정하고 동일한 테스트가 통과하는지 확인한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TravelUpdateConsistencyTest {

    private static final Long USER_ID = 1L;
    private static final Long TRAVEL_ID = 100L;
    private static final String REMOVED_PHOTO_URL = "https://cdn.example.com/removed.jpg";

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
    private GcsService gcsService;
    @Mock
    private GeoCodingService geoCodingService;

    private TravelService travelService;
    private User owner;
    private Travel travel;
    private TravelDay day;

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

        owner = User.builder()
                .id(USER_ID)
                .email("owner@example.com")
                .password("encoded-password")
                .build();

        travel = Travel.builder()
                .id(TRAVEL_ID)
                .user(owner)
                .title("기존 여행")
                .startDate(LocalDate.of(2026, 7, 29))
                .endDate(LocalDate.of(2026, 7, 29))
                .representativePhotoId(1L)
                .build();

        day = TravelDay.builder()
                .id(10L)
                .travel(travel)
                .dayNumber(1)
                .date(LocalDate.of(2026, 7, 29))
                .build();

        when(travelRepository.findById(TRAVEL_ID)).thenReturn(Optional.of(travel));
        when(travelDayRepository.findByTravelId(TRAVEL_ID)).thenReturn(List.of(day));
    }

    @Test
    void 모든_검증이_끝나기_전에는_R2_파일을_삭제하지_않는다() {
        Photo removedPhoto = photo(1L, REMOVED_PHOTO_URL, day);
        when(photoRepository.findByTravelDayIn(anyList())).thenReturn(List.of(removedPhoto));
        when(photoRepository.findById(999L)).thenReturn(Optional.empty());

        TravelUpdateRequest request = updateRequest(List.of(999L), null);

        assertThrows(
                IllegalArgumentException.class,
                () -> travelService.updateTravel(TRAVEL_ID, request, owner)
        );

        verify(gcsService, never()).deleteFile(any());
        verify(photoRepository, never()).delete(removedPhoto);
    }

    @Test
    void 새로_추가하는_사진도_최종_목록에_있으면_대표_사진으로_설정할_수_있다() {
        Photo keptPhoto = photo(1L, "https://cdn.example.com/kept.jpg", day);
        Photo addedPhoto = photo(2L, "https://cdn.example.com/added.jpg", null);

        when(photoRepository.findByTravelDayIn(anyList())).thenReturn(List.of(keptPhoto));
        when(photoRepository.findById(1L)).thenReturn(Optional.of(keptPhoto));
        when(photoRepository.findById(2L)).thenReturn(Optional.of(addedPhoto));
        when(photoRepository.findAllById(any())).thenReturn(List.of(keptPhoto, addedPhoto));
        when(photoRepository.findByTravelDay(day)).thenReturn(List.of(keptPhoto));

        TravelUpdateRequest request = updateRequest(List.of(1L, 2L), 2L);

        assertDoesNotThrow(() -> travelService.updateTravel(TRAVEL_ID, request, owner));

        assertEquals(2L, travel.getRepresentativePhotoId());
        assertEquals(day, addedPhoto.getTravelDay());
    }

    @Test
    void R2_삭제는_DB_커밋이_성공한_뒤에_실행한다() throws Exception {
        Photo removedPhoto = photo(1L, REMOVED_PHOTO_URL, day);
        Photo keptPhoto = photo(2L, "https://cdn.example.com/kept.jpg", day);
        prepareRemoveOnePhoto(removedPhoto, keptPhoto);

        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch deleteCalled = new CountDownLatch(1);
        AtomicBoolean deletionObservedAfterCommit = new AtomicBoolean(false);

        doAnswer(invocation -> {
            deletionObservedAfterCommit.set(transactionManager.isCommitted());
            deleteCalled.countDown();
            return null;
        }).when(gcsService).deleteFile(REMOVED_PHOTO_URL);

        transactionTemplate.executeWithoutResult(status ->
                travelService.updateTravel(TRAVEL_ID, updateRequest(List.of(2L), null), owner)
        );

        assertTrue(deleteCalled.await(2, TimeUnit.SECONDS), "R2 삭제가 호출되지 않았습니다.");
        assertTrue(deletionObservedAfterCommit.get(), "DB 커밋 전에 R2 파일을 삭제했습니다.");
        verify(photoRepository).delete(removedPhoto);
    }

    @Test
    void 커밋후_R2_삭제실패는_성공한_DB_수정을_실패로_바꾸지_않는다() throws Exception {
        Photo removedPhoto = photo(1L, REMOVED_PHOTO_URL, day);
        Photo keptPhoto = photo(2L, "https://cdn.example.com/kept.jpg", day);
        prepareRemoveOnePhoto(removedPhoto, keptPhoto);

        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch deleteCalled = new CountDownLatch(1);
        AtomicBoolean deletionObservedAfterCommit = new AtomicBoolean(false);

        doAnswer(invocation -> {
            deletionObservedAfterCommit.set(transactionManager.isCommitted());
            deleteCalled.countDown();
            throw new IllegalStateException("R2 temporary failure");
        }).when(gcsService).deleteFile(REMOVED_PHOTO_URL);

        assertDoesNotThrow(() -> transactionTemplate.executeWithoutResult(status ->
                travelService.updateTravel(TRAVEL_ID, updateRequest(List.of(2L), null), owner)
        ));

        assertTrue(deleteCalled.await(2, TimeUnit.SECONDS), "R2 삭제가 호출되지 않았습니다.");
        assertTrue(deletionObservedAfterCommit.get(), "R2 삭제가 커밋 전에 실행됐습니다.");
        assertTrue(transactionManager.isCommitted(), "DB 트랜잭션이 커밋되지 않았습니다.");
        verify(photoRepository).delete(removedPhoto);
    }

    private void prepareRemoveOnePhoto(Photo removedPhoto, Photo keptPhoto) {
        when(photoRepository.findByTravelDayIn(anyList())).thenReturn(List.of(removedPhoto, keptPhoto));
        when(photoRepository.findById(2L)).thenReturn(Optional.of(keptPhoto));
        when(photoRepository.findAllById(any())).thenReturn(List.of(keptPhoto));
        when(photoRepository.findByTravelDay(day)).thenReturn(List.of(keptPhoto));
    }

    private Photo photo(Long id, String url, TravelDay travelDay) {
        return Photo.builder()
                .id(id)
                .user(owner)
                .travelDay(travelDay)
                .filePath(url)
                .originalName(id + ".jpg")
                .takenAt(LocalDateTime.of(2026, 7, 29, 12, 0))
                .build();
    }

    private TravelUpdateRequest updateRequest(List<Long> photoIds, Long representativePhotoId) {
        TravelUpdateRequest request = new TravelUpdateRequest();
        request.setPhotoIds(photoIds);
        request.setRepresentativePhotoId(representativePhotoId);
        return request;
    }

    /**
     * 실제 DB 없이도 TransactionSynchronization의 before/after commit 순서를 실행하는 테스트용 TM.
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final AtomicBoolean committed = new AtomicBoolean(false);

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
