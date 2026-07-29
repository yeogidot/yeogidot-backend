package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.BadRequestException;
import com.yeogidot.yeogidot.exception.ForbiddenException;
import com.yeogidot.yeogidot.exception.ResourceNotFoundException;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.repository.TravelLogRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelServiceTest {

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

    @BeforeEach
    void setUp() {
        travelService = new TravelService(
                travelRepository,
                userRepository,
                photoRepository,
                travelDayRepository,
                travelLogRepository,
                gcsService,
                geoCodingService
        );
    }

    @Test
    void 본인_사진과_올바른_대표_사진이면_기존_여행_생성_흐름을_실행한다() {
        User user = user(1L);
        Photo photo = photo(10L, user);
        TravelDto.CreateRequest request = request(List.of(10L), 10L);
        when(photoRepository.findAllById(any())).thenReturn(List.of(photo));

        assertDoesNotThrow(() -> travelService.createTravel(request, user));

        verify(travelRepository).save(any());
        verify(travelDayRepository).save(any());
        verify(photoRepository).save(photo);
    }

    @Test
    void 다른_사용자의_사진이면_여행을_저장하기_전에_차단한다() {
        User currentUser = user(1L);
        Photo otherUsersPhoto = photo(10L, user(2L));
        TravelDto.CreateRequest request = request(List.of(10L), 10L);
        when(photoRepository.findAllById(any())).thenReturn(List.of(otherUsersPhoto));

        assertThrows(
                ForbiddenException.class,
                () -> travelService.createTravel(request, currentUser)
        );

        verify(travelRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_사진이_포함되면_여행을_저장하기_전에_차단한다() {
        User user = user(1L);
        TravelDto.CreateRequest request = request(List.of(10L, 20L), 10L);
        when(photoRepository.findAllById(any())).thenReturn(List.of(photo(10L, user)));

        assertThrows(
                ResourceNotFoundException.class,
                () -> travelService.createTravel(request, user)
        );

        verify(travelRepository, never()).save(any());
    }

    @Test
    void 대표_사진이_요청_사진에_없으면_여행을_저장하기_전에_차단한다() {
        User user = user(1L);
        TravelDto.CreateRequest request = request(List.of(10L), 20L);
        when(photoRepository.findAllById(any())).thenReturn(List.of(photo(10L, user)));

        assertThrows(
                BadRequestException.class,
                () -> travelService.createTravel(request, user)
        );

        verify(travelRepository, never()).save(any());
    }

    private User user(Long id) {
        return User.builder()
                .id(id)
                .email("user" + id + "@example.com")
                .password("encoded-password")
                .build();
    }

    private Photo photo(Long id, User user) {
        return Photo.builder()
                .id(id)
                .user(user)
                .filePath("https://example.com/" + id + ".jpg")
                .takenAt(LocalDateTime.of(2026, 7, 28, 12, 0))
                .build();
    }

    private TravelDto.CreateRequest request(
            List<Long> photoIds,
            Long representativePhotoId
    ) {
        return new TravelDto.CreateRequest(
                "테스트 여행",
                "서울특별시",
                null,
                null,
                photoIds,
                representativePhotoId
        );
    }
}
