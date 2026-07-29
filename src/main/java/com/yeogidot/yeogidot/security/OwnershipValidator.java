package com.yeogidot.yeogidot.security;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.TravelLog;
import com.yeogidot.yeogidot.exception.ForbiddenException;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 도메인 리소스의 소유권 검증을 한 곳에서 처리한다.
 * 기존 API의 상태 코드와 메시지를 유지할 수 있도록 단일 리소스 검증은
 * 호출부가 전달한 메시지로 SecurityException을 발생시킨다.
 */
@Component
public class OwnershipValidator {

    public void validatePhotoOwner(Photo photo, Long userId, String message) {
        if (!photo.getUser().getId().equals(userId)) {
            throw new SecurityException(message);
        }
    }

    public void validatePhotoOwners(Collection<Photo> photos, Long userId) {
        boolean containsOtherUserPhoto = photos.stream()
                .anyMatch(photo -> !photo.getUser().getId().equals(userId));

        if (containsOtherUserPhoto) {
            throw new ForbiddenException("사용할 수 없는 사진이 포함되어 있습니다.");
        }
    }

    public void validateTravelOwner(Travel travel, Long userId, String message) {
        if (!travel.getUser().getId().equals(userId)) {
            throw new SecurityException(message);
        }
    }

    public void validateTravelDayOwner(TravelDay travelDay, Long userId, String message) {
        validateTravelOwner(travelDay.getTravel(), userId, message);
    }

    public void validateTravelLogOwner(TravelLog travelLog, Long userId, String message) {
        validateTravelDayOwner(travelLog.getTravelDay(), userId, message);
    }
}
