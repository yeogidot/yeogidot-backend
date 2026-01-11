package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public Long createTravel(TravelDto.CreateRequest request, User user) {

        // 1단계: 여행 기간 결정 (사진 기반 자동 생성 또는 수동 입력)
        LocalDate startDate;
        LocalDate endDate;

        if (request.getStartDate() == null || request.getEndDate() == null) {
            // 사진 날짜 기반 자동 생성
            if (request.getPhotoIds() == null || request.getPhotoIds().isEmpty()) {
                throw new IllegalArgumentException("날짜를 입력하거나 사진을 업로드해주세요.");
            }

            // 사진들의 촬영 날짜 수집
            List<LocalDate> photoDates = request.getPhotoIds().stream()
                    .map(photoId -> photoRepository.findById(photoId).orElse(null))
                    .filter(photo -> photo != null && photo.getTakenAt() != null)
                    .map(photo -> photo.getTakenAt().toLocalDate())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            if (photoDates.isEmpty()) {
                throw new IllegalArgumentException("사진에 촬영 날짜 정보가 없습니다.");
            }

            startDate = photoDates.get(0); // 가장 이른 날짜
            endDate = photoDates.get(photoDates.size() - 1); // 가장 늦은 날짜
        } else {
            // 사용자가 직접 입력한 날짜 사용
            startDate = request.getStartDate();
            endDate = request.getEndDate();
        }

        // 2단계: 여행 기록 생성
        Travel travel = Travel.builder()
                .title(request.getTitle())
                .trvRegion(request.getTrvRegion())
                .startDate(startDate)
                .endDate(endDate)
                .representativePhotoId(request.getRepresentativePhotoId())
                .user(user)
                .build();

        travelRepository.save(travel);

        //  3단계: 날짜별 TravelDay 자동 생성
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Map<LocalDate, TravelDay> dayMap = new HashMap<>();

        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            TravelDay day = TravelDay.builder()
                    .travel(travel)
                    .dayNumber(i + 1)
                    .date(date)
                    .build();
            travelDayRepository.save(day);
            dayMap.put(date, day);
        }

        // 4단계: 사진 자동 분류 (촬영 날짜 기준)
        if (request.getPhotoIds() != null && !request.getPhotoIds().isEmpty()) {
            for (Long photoId : request.getPhotoIds()) {
                Photo photo = photoRepository.findById(photoId).orElse(null);
                if (photo != null && photo.getTakenAt() != null) {
                    // 촬영 날짜를 LocalDate로 변환
                    LocalDate photoDate = photo.getTakenAt().toLocalDate();

                    // 해당 날짜에 맞는 TravelDay 찾기
                    TravelDay matchingDay = dayMap.get(photoDate);

                    if (matchingDay != null) {
                        // 여행 기간 내의 사진이면 해당 날짜에 배치
                        photo.setTravelDay(matchingDay);
                    } else if (photoDate.isBefore(startDate)) {
                        // 여행 전 사진은 첫날에 배치
                        photo.setTravelDay(dayMap.get(startDate));
                    } else {
                        // 여행 후 사진은 마지막 날에 배치
                        photo.setTravelDay(dayMap.get(endDate));
                    }
                }
            }
        }

        return travel.getId();
    }

    // === 여행 상세 조회 ===
    public TravelDto.DetailResponse getTravelDetail(Long travelId, User user) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다. ID=" + travelId));

        // 권한 검증
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 여행을 조회할 권한이 없습니다.");
        }

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
    public TravelDto.DayDetailResponse getTravelDayDetail(Long travelId, Integer dayNumber, User user) {
        TravelDay day = travelDayRepository.findByTravelIdAndDayNumber(travelId, dayNumber)
                .orElseThrow(() -> new IllegalArgumentException("해당 일차 정보를 찾을 수 없습니다."));

        // 권한 검증
        if (!day.getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("조회 권한이 없습니다.");
        }

        return new TravelDto.DayDetailResponse(
                day.getId(),
                day.getDayNumber(),
                day.getDate(),
                day.getDayRegion()
        );
    }

    // === 여행 일차 삭제 ===
    @Transactional
    public void deleteTravelDay(Long dayId, User user) {
        TravelDay day = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일차입니다."));
        
        // 권한 검증
        if (!day.getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("삭제 권한이 없습니다.");
        }
        
        // TravelDay 삭제 전에 속한 사진들의 travelDay를 null로 설정
        // (사진은 삭제하지 않고 여행과의 연결만 해제)
        for (Photo photo : day.getPhotos()) {
            photo.setTravelDay(null);
        }
        
        travelDayRepository.delete(day);
    }

    // === 여행 일차 수동 추가  ===
    @Transactional
    public Long addTravelDay(Long travelId, TravelDto.AddDayRequest request, User user) {
        // 여행 조회 및 권한 확인
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행입니다."));
        
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }

        // 이미 존재하는 날짜인지 확인
        boolean alreadyExists = travel.getTravelDays().stream()
                .anyMatch(day -> day.getDate().equals(request.getDate()));
        
        if (alreadyExists) {
            throw new IllegalArgumentException("해당 날짜는 이미 존재합니다.");
        }

        // 새로운 날짜의 dayNumber 계산 (날짜 순서대로 정렬 후 위치 찾기)
        List<TravelDay> sortedDays = travel.getTravelDays().stream()
                .sorted((d1, d2) -> d1.getDate().compareTo(d2.getDate()))
                .collect(Collectors.toList());

        int newDayNumber = 1;
        for (TravelDay day : sortedDays) {
            if (request.getDate().isBefore(day.getDate())) {
                break;
            }
            newDayNumber++;
        }

        // TravelDay 생성
        TravelDay newDay = TravelDay.builder()
                .travel(travel)
                .dayNumber(newDayNumber)
                .date(request.getDate())
                .build();
        
        TravelDay savedDay = travelDayRepository.save(newDay);

        // 이후 날짜들의 dayNumber 재정렬
        for (TravelDay day : sortedDays) {
            if (day.getDate().isAfter(request.getDate())) {
                day.updateDayNumber(day.getDayNumber() + 1);
            }
        }

        // Travel의 startDate, endDate 업데이트
        LocalDate newStartDate = travel.getStartDate();
        LocalDate newEndDate = travel.getEndDate();
        
        if (request.getDate().isBefore(travel.getStartDate())) {
            newStartDate = request.getDate();
        }
        if (request.getDate().isAfter(travel.getEndDate())) {
            newEndDate = request.getDate();
        }

        // Travel 엔티티 업데이트
        if (!newStartDate.equals(travel.getStartDate()) || !newEndDate.equals(travel.getEndDate())) {
            travel.updateDates(newStartDate, newEndDate);
        }

        return savedDay.getId();
    }

    // === 여행 로그 생성/수정 ===
    @Transactional
    public Long createTravelLog(Long dayId, TravelDto.LogRequest request, User user) {
        TravelDay day = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일차입니다."));
        
        // 권한 검증
        if (!day.getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }

        TravelLog log = TravelLog.builder()
                .travelDay(day)
                .content(request.getContent())
                .build();

        return travelLogRepository.save(log).getId();
    }

    @Transactional
    public void updateTravelLog(Long logId, TravelDto.LogRequest request, User user) {
        TravelLog log = travelLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일기입니다."));
        
        // 권한 검증
        if (!log.getTravelDay().getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }
        
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
            String uuid = UUID.randomUUID().toString();
            String baseUrl = "https://travel.vercel.app/share/";
            String fullUrl = baseUrl + uuid;

            // Travel 엔티티에 shareUrl 업데이트
            travel.updateShareUrl(fullUrl);
        }

        return TravelDto.ShareUrlResponse.builder()
                .travelId(travel.getId())
                .shareUrl(travel.getShareUrl())
                .build();
    }

    // === 대표 사진 수정  ===
    @Transactional
    public void updateRepresentativePhoto(Long travelId, Long photoId, User user) {
        // 여행 조회
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("여행이 존재하지 않습니다."));

        // 권한 검증
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }

        // 사진 존재 여부 확인 (선택적)
        if (photoId != null) {
            photoRepository.findById(photoId)
                    .orElseThrow(() -> new IllegalArgumentException("사진이 존재하지 않습니다."));
        }

        // 대표 사진 업데이트
        travel.updateRepresentativePhoto(photoId);
    }
}