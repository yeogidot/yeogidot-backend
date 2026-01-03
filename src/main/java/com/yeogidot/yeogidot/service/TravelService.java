package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

// 여행 서비스

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TravelService {

    private final TravelRepository travelRepository;
    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;
    private final TravelDayRepository travelDayRepository;
    private final TravelLogRepository travelLogRepository;

    // === 여행 목록 조회 ===
    public List<TravelDto.Info> getMyTravels(User user) {
        List<Travel> travels = travelRepository.findAllByUserOrderByIdDesc(user);

        return travels.stream().map(travel -> {
            String photoUrl = null;
            if (travel.getRepresentativePhotoId() != null) {
                photoUrl = photoRepository.findById(travel.getRepresentativePhotoId())
                        .map(Photo::getFilePath)
                        .orElse(null);
            }

            return TravelDto.Info.builder()
                    .travelId(travel.getId())
                    .title(travel.getTitle())
                    .trvRegion(travel.getTrvRegion())
                    .startDate(travel.getStartDate())
                    .endDate(travel.getEndDate())
                    .representativeImageUrl(photoUrl)
                    .build();
        }).collect(Collectors.toList());
    }

    // === 여행 생성 ===
    @Transactional
    public Long createTravel(TravelDto.CreateRequest request, User user) { // User 파라미터 추가됨

        // 여행 기록 생성 (로그인한 유저로 저장)
        Travel travel = Travel.builder()
                .title(request.getTitle())
                .trvRegion(request.getTrvRegion())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .user(user)
                .build();

        travelRepository.save(travel);

        // 날짜 차이 계산해서 TravelDay 자동 생성
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;

        for (int i = 0; i < days; i++) {
            TravelDay day = TravelDay.builder()
                    .travel(travel)
                    .dayNumber(i + 1)
                    .date(request.getStartDate().plusDays(i))
                    .build();
            travelDayRepository.save(day);

            // 만약 요청에 사진 ID들이 있고, 1일 차라면 사진을 연결
            if (i == 0 && request.getPhotoIds() != null && !request.getPhotoIds().isEmpty()) {
                for (Long photoId : request.getPhotoIds()) {
                    Photo photo = photoRepository.findById(photoId).orElse(null);
                    if (photo != null) {
                        photo.setTravelDay(day); // 사진에 일차 생성
                    }
                }
            }
        }

        return travel.getId();
    }

    // === 여행 상세 조회 ===
    public TravelDto.DetailResponse getTravelDetail(Long travelId) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다. ID=" + travelId));

        return TravelDto.DetailResponse.builder()
                .travelId(travel.getId())
                .title(travel.getTitle())
                .representativePhotoId(travel.getRepresentativePhotoId())
                .shareUrl(travel.getShareUrl())
                .startDate(travel.getStartDate())
                .endDate(travel.getEndDate())
                .days(travel.getTravelDays().stream()
                        .map(this::mapToDayDetail)
                        .collect(Collectors.toList()))
                .build();
    }

    // === 여행 삭제 ===
    @Transactional
    public void deleteTravel(Long travelId, User user) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행입니다."));

        if (!travel.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("삭제 권한이 없습니다.");
        }
        travelRepository.delete(travel);
    }

    // === 여행 일차 상세 조회 ===
    public TravelDto.DayDetailResponse getTravelDayDetail(Long travelId, Integer dayNumber) {
        TravelDay day = travelDayRepository.findByTravelIdAndDayNumber(travelId, dayNumber)
                .orElseThrow(() -> new IllegalArgumentException("해당 일차 정보를 찾을 수 없습니다."));

        return new TravelDto.DayDetailResponse(
                day.getId(),
                day.getDayNumber(),
                day.getDate(),
                day.getDayRegion()
        );
    }

    // === 여행 일차 삭제 ===
    @Transactional
    public void deleteTravelDay(Long dayId) {
        TravelDay day = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일차입니다."));
        travelDayRepository.delete(day);
    }

    // === 여행 로그 생성/수정 ===
    @Transactional
    public Long createTravelLog(Long dayId, TravelDto.LogRequest request) {
        TravelDay day = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일차입니다."));

        TravelLog log = TravelLog.builder()
                .travelDay(day)
                .content(request.getContent())
                .build();

        return travelLogRepository.save(log).getId();
    }

    @Transactional
    public void updateTravelLog(Long logId, TravelDto.LogRequest request) {
        TravelLog log = travelLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일기입니다."));
        log.updateContent(request.getContent());
    }

    // --- 헬퍼 메서드: Day 엔티티 -> DTO 변환 ---
    private TravelDto.TravelDayDetail mapToDayDetail(TravelDay day) {
        List<TravelDto.PhotoDetail> photoDetails = day.getPhotos().stream()
                .map(photo -> TravelDto.PhotoDetail.builder()
                        .photoId(photo.getId())
                        .url(photo.getFilePath())
                        .takenAt(photo.getTakenAt())
                        .latitude(photo.getLatitude())
                        .longitude(photo.getLongitude())
                        .build())
                .collect(Collectors.toList());

        TravelDto.DiaryDetail diaryDetail = null;
        if (!day.getTravelLogs().isEmpty()) {
            TravelLog log = day.getTravelLogs().get(0);
            diaryDetail = TravelDto.DiaryDetail.builder()
                    .logId(log.getId())
                    .content(log.getContent())
                    .logCreated(log.getCreatedDate())
                    .build();
        }

        return TravelDto.TravelDayDetail.builder()
                .dayId(day.getId())
                .dayNumber(day.getDayNumber())
                .date(day.getDate())
                .dayRegion(day.getDayRegion())
                .photos(photoDetails)
                .diary(diaryDetail)
                .build();
    }

    // === 여행 공유 URL 조회 및 생성 ===
    @Transactional
    public TravelDto.ShareUrlResponse getOrCreateShareUrl(Long travelId, User user) {
        // 여행지 존재 여부 확인
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행 기록입니다."));

        // 본인 여부 확인
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("해당 여행을 공유할 권한이 없습니다.");
        }

        // DB에 share_url이 없으면 새로 생성
        if (travel.getShareUrl() == null || travel.getShareUrl().isEmpty()) {
            String uuid = UUID.randomUUID().toString(); // 예측 불가능한 랜덤 문자열 생성
            String baseUrl = "https://travel.vercel.app/share/"; // 프론트엔드 접속 주소 예시
            String fullUrl = baseUrl + uuid;

            // DB 업데이트
            // Travel 엔티티에 shareUrl을 변경하는 메서드가 없으므로 직접 필드에 접근하거나 Setter가 필요합니다.
            // 여기서는 @Builder로 생성된 엔티티의 필드에 직접 접근하거나 별도의 업데이트 로직을 사용한다고 가정합니다.
            // (Travel 엔티티에 직접적인 업데이트 로직이 없으므로, 편의상 엔티티 내부 필드 수정을 반영하는 로직으로 작성합니다.)

            // 엔티티 필드 업데이트 (JPA Dirty Checking 활용)
            // 실제 코드에선 travel.setShareUrl(fullUrl) 등을 구현해야 합니다.
            // 현재 엔티티 구조상 reflection이나 Setter 추가가 필요할 수 있습니다.
            try {
                java.lang.reflect.Field field = Travel.class.getDeclaredField("shareUrl");
                field.setAccessible(true);
                field.set(travel, fullUrl);
            } catch (Exception e) {
                throw new RuntimeException("공유 URL 업데이트 중 오류 발생");
            }
        }

        return TravelDto.ShareUrlResponse.builder()
                .travelId(travel.getId())
                .shareUrl(travel.getShareUrl())
                .build();
    }
}