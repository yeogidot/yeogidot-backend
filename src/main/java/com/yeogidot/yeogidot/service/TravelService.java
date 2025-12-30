package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

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
    public Long createTravel(TravelDto.CreateRequest request) {
        // 실제 서비스 시 getCurrentUser 로직과 연동 필요
        User user = userRepository.findById(1L)
                .orElseGet(() -> userRepository.save(User.create("test@test.com", "1234")));

        Travel travel = Travel.builder()
                .title(request.getTitle())
                .trvRegion(request.getTrvRegion())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .user(user)
                .build();

        return travelRepository.save(travel).getId();
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
}