package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    @Mock
    private PhotoRepository photoRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private GcsService gcsService;
    @Mock
    private GeoCodingService geoCodingService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TravelDayRepository travelDayRepository;

    private PhotoService photoService;

    @BeforeEach
    void setUp() {
        photoService = new PhotoService(
                photoRepository,
                commentRepository,
                gcsService,
                geoCodingService,
                objectMapper,
                travelDayRepository,
                new OwnershipValidator()
        );
    }

    @Test
    void 사진_소유자는_사진을_조회할_수_있다() {
        Photo photo = Photo.builder().id(10L).user(user(1L)).build();
        when(photoRepository.findById(10L)).thenReturn(Optional.of(photo));

        Photo result = photoService.getPhotoById(10L, 1L);

        assertSame(photo, result);
    }

    @Test
    void 사진_소유자가_아니면_사진_조회를_차단한다() {
        Photo photo = Photo.builder().id(10L).user(user(2L)).build();
        when(photoRepository.findById(10L)).thenReturn(Optional.of(photo));

        assertThrows(
                SecurityException.class,
                () -> photoService.getPhotoById(10L, 1L)
        );
    }

    private User user(Long id) {
        return User.builder()
                .id(id)
                .email("user" + id + "@example.com")
                .password("encoded-password")
                .build();
    }
}
