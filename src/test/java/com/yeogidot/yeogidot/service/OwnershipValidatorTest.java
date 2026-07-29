package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.TravelLog;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.ForbiddenException;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnershipValidatorTest {

    private final OwnershipValidator ownershipValidator = new OwnershipValidator();

    @Test
    void 사진_소유자는_검증을_통과한다() {
        Photo photo = Photo.builder().user(user(1L)).build();

        assertDoesNotThrow(() ->
                ownershipValidator.validatePhotoOwner(photo, 1L, "사진 권한이 없습니다.")
        );
    }

    @Test
    void 사진_소유자가_아니면_기존_메시지로_차단한다() {
        Photo photo = Photo.builder().user(user(2L)).build();

        SecurityException exception = assertThrows(
                SecurityException.class,
                () -> ownershipValidator.validatePhotoOwner(
                        photo,
                        1L,
                        "사진 수정 권한이 없습니다."
                )
        );

        assertEquals("사진 수정 권한이 없습니다.", exception.getMessage());
    }

    @Test
    void 여러_사진에_다른_사용자_사진이_포함되면_차단한다() {
        List<Photo> photos = List.of(
                Photo.builder().user(user(1L)).build(),
                Photo.builder().user(user(2L)).build()
        );

        assertThrows(
                ForbiddenException.class,
                () -> ownershipValidator.validatePhotoOwners(photos, 1L)
        );
    }

    @Test
    void 여행_일기_검증은_연결된_여행의_소유자를_확인한다() {
        Travel travel = Travel.builder().user(user(2L)).build();
        TravelDay travelDay = TravelDay.builder().travel(travel).build();
        TravelLog travelLog = TravelLog.builder().travelDay(travelDay).build();

        assertThrows(
                SecurityException.class,
                () -> ownershipValidator.validateTravelLogOwner(
                        travelLog,
                        1L,
                        "권한이 없습니다."
                )
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
