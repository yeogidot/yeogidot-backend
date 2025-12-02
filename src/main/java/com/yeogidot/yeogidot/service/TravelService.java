package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.TravelDay;
import com.yeogidot.yeogidot.entity.TravelLog;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [여행 서비스]
 * - 여행 기록과 관련된 핵심 비즈니스 로직을 처리합니다.
 * - DB 조회 및 저장, Entity <-> DTO 변환을 수행합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본적으로 읽기 전용 트랜잭션 (성능 최적화)
public class TravelService {

    private final TravelRepository travelRepository;
    private final UserRepository userRepository;

    /**
     * 여행 상세 조회
     * - 여행 ID로 DB에서 데이터를 찾아 DTO로 변환하여 반환합니다.
     */
    public TravelDto.DetailResponse getTravelDetail(Long travelId) {
        // 1. 여행 조회 (없으면 예외 발생)
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다. ID=" + travelId));

        // 2. Entity -> Response DTO 변환
        // 여행 하위의 '일차(Days)' 목록도 DTO로 변환해서 넣어줍니다.
        return TravelDto.DetailResponse.builder()
                .travelId(travel.getId())
                .title(travel.getTitle())
                .representativePhotoId(travel.getRepresentativePhotoId())
                .shareUrl(travel.getShareUrl())
                .startDate(travel.getStartDate())
                .endDate(travel.getEndDate())
                .days(travel.getDays().stream()
                        .map(this::mapToDayDetail) // 아래 헬퍼 메서드 사용
                        .collect(Collectors.toList()))
                .build();
    }

    /**
     * [헬퍼 메서드] TravelDay 엔티티를 TravelDayDetail DTO로 변환
     * - 일차(Day) 안에 있는 사진(Photo)과 일기(Log) 정보도 함께 변환합니다.
     */
    private TravelDto.TravelDayDetail mapToDayDetail(TravelDay day) {
        // 1. 사진 리스트 변환
        List<TravelDto.PhotoDetail> photoDetails = day.getPhotos().stream()
                .map(photo -> TravelDto.PhotoDetail.builder()
                        .photoId(photo.getId())
                        .url(photo.getFilePath())
                        .takenAt(photo.getTakenAt())
                        .latitude(photo.getLatitude())
                        .longitude(photo.getLongitude())
                        .build())
                .collect(Collectors.toList());

        // 2. 일기(Log) 변환 (있을 경우에만)
        TravelDto.DiaryDetail diaryDetail = null;
        if (day.getTravelLog() != null) {
            TravelLog log = day.getTravelLog();
            diaryDetail = TravelDto.DiaryDetail.builder()
                    .logId(log.getId())
                    .content(log.getContent())
                    .logCreated(log.getCreatedDate())
                    .build();
        }

        // 3. DTO 조립 및 반환
        return TravelDto.TravelDayDetail.builder()
                .dayId(day.getId())
                .dayNumber(day.getDayNumber())
                .date(day.getDate())
                .dayRegion(day.getDayRegion())
                .photos(photoDetails)
                .diary(diaryDetail)
                .build();
    }

    /**
     * 여행 생성
     * - 쓰기 작업이므로 @Transactional을 붙여 읽기 전용을 해제합니다.
     */
    @Transactional
    public Long createTravel(TravelDto.CreateRequest request) {
        // 1. 사용자 조회 (임시 로직: 1번 유저가 없으면 새로 생성)
        // 실제 서비스에서는 로그인한 사용자의 ID를 받아와야 합니다.
        User user = userRepository.findById(1L)
                .orElseGet(() -> userRepository.save(User.create("test@test.com", "1234")));

        // 2. 여행 엔티티 생성 (Builder 패턴 사용)
        Travel travel = Travel.builder()
                .title(request.getTitle())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .user(user)
                .build();

        // 3. DB 저장 후 ID 반환
        Travel savedTravel = travelRepository.save(travel);
        return savedTravel.getId();
    }
}