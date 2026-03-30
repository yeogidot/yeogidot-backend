package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.exception.ResourceNotFoundException;
import com.yeogidot.yeogidot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

// 여행 서비스
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TravelService {

    private final TravelRepository travelRepository;
    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;
    private final TravelDayRepository travelDayRepository;
    private final TravelLogRepository travelLogRepository;
    private final GcsService gcsService;
    private final GeoCodingService geoCodingService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;


    // === 여행 목록 조회 ===
    public List<TravelDto.Info> getMyTravels(User user) {
        List<Travel> travels = travelRepository.findAllByUserOrderByIdDesc(user);

        // N+1 개선: 대표 사진 ID 목록을 모아 IN절로 한 번에 조회
        List<Long> representativePhotoIds = travels.stream()
                .map(Travel::getRepresentativePhotoId)
                .filter(id -> id != null)
                .collect(Collectors.toList());

        Map<Long, String> photoUrlMap = photoRepository.findAllById(representativePhotoIds)
                .stream()
                .collect(Collectors.toMap(Photo::getId, Photo::getFilePath));

        return travels.stream().map(travel -> {
            String photoUrl = travel.getRepresentativePhotoId() != null
                    ? photoUrlMap.get(travel.getRepresentativePhotoId())
                    : null;

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

        // 1단계: 사진 유효성 검증
        if (request.getPhotoIds() == null || request.getPhotoIds().isEmpty()) {
            throw new IllegalArgumentException("최소 1장 이상의 사진을 선택해주세요.");
        }

        // 2단계: 사진들의 정보 수집
        List<Photo> photos = request.getPhotoIds().stream()
                .map(photoId -> photoRepository.findById(photoId).orElse(null))
                .filter(photo -> photo != null && photo.getTakenAt() != null)
                .collect(Collectors.toList());

        if (photos.isEmpty()) {
            throw new IllegalArgumentException("사진에 촬영 날짜 정보가 없습니다.");
        }

        // 3단계: 사진 날짜 수집
        List<LocalDate> photoDates = photos.stream()
                .map(photo -> photo.getTakenAt().toLocalDate())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // 4단계: 여행 기간 결정
        LocalDate startDate;
        LocalDate endDate;

        if (request.getStartDate() != null && request.getEndDate() != null) {
            // 사용자가 직접 입력한 날짜 사용
            startDate = request.getStartDate();
            endDate = request.getEndDate();
        } else {
            // 사진 날짜 기반 자동 생성
            startDate = photoDates.get(0);
            endDate = photoDates.get(photoDates.size() - 1);
        }

        // 5단계: 지역명 자동 결정 (위도/경도 기반)
        String trvRegion = request.getTrvRegion();
        if (trvRegion == null || trvRegion.isEmpty()) {
            // 가장 많이 등장하는 지역명 찾기
            Map<String, Long> regionCount = photos.stream()
                    .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                    .map(photo -> geoCodingService.getRegionFromCoordinates(
                            photo.getLatitude(),
                            photo.getLongitude()
                    ))
                    .filter(region -> region != null)
                    .collect(Collectors.groupingBy(
                            region -> region,
                            Collectors.counting()
                    ));

            trvRegion = regionCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("미지정");

            log.info("🗺️ 자동 지역 설정: {}", trvRegion);
        }

        // 6단계: 여행 기록 생성
        Travel travel = Travel.builder()
                .title(request.getTitle())
                .trvRegion(trvRegion) // 자동 설정된 지역명
                .startDate(startDate)
                .endDate(endDate)
                .representativePhotoId(request.getRepresentativePhotoId())
                .user(user)
                .build();

        travelRepository.save(travel);

        // 7단계: 사진이 있는 날짜만 TravelDay 생성
        Map<LocalDate, TravelDay> dayMap = new HashMap<>();
        int dayNumber = 1;

        for (LocalDate photoDate : photoDates) {
            TravelDay day = TravelDay.builder()
                    .travel(travel)
                    .dayNumber(dayNumber++)
                    .date(photoDate)
                    .build();
            travelDayRepository.save(day);
            dayMap.put(photoDate, day);
        }

        // 8단계: 사진을 해당 날짜의 TravelDay에 배치하고 dayRegion 설정
        for (Long photoId : request.getPhotoIds()) {
            Photo photo = photoRepository.findById(photoId).orElse(null);
            if (photo != null && photo.getTakenAt() != null) {
                LocalDate photoDate = photo.getTakenAt().toLocalDate();
                TravelDay matchingDay = dayMap.get(photoDate);

                if (matchingDay != null) {
                    photo.setTravelDay(matchingDay);
                    photoRepository.save(photo);
                }
            }
        }

        // 9단계: 각 TravelDay의 dayRegion 자동 설정
        for (LocalDate photoDate : photoDates) {
            TravelDay day = dayMap.get(photoDate);

            // 해당 날짜의 사진들 수집
            List<Photo> dayPhotos = photos.stream()
                    .filter(p -> p.getTakenAt().toLocalDate().equals(photoDate))
                    .collect(Collectors.toList());

            // 사진 위치 기반으로 dayRegion 결정
            Map<String, Long> regionCount = dayPhotos.stream()
                    .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                    .map(photo -> {
                        GeoCodingService.RegionInfo regionInfo = geoCodingService.getDetailedRegion(
                                photo.getLatitude(),
                                photo.getLongitude()
                        );
                        return regionInfo != null ? regionInfo.getRegion2depth() : null;
                    })
                    .filter(region -> region != null)
                    .collect(Collectors.groupingBy(
                            region -> region,
                            Collectors.counting()
                    ));

            String dayRegion = regionCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (dayRegion != null) {
                day.updateDayRegion(dayRegion);
                travelDayRepository.save(day);
                log.info("✅ 일차 {} 지역 설정: {}", day.getDayNumber(), dayRegion);
            }
        }

        return travel.getId();
    }

    // === 여행 상세 조회 (N+1 해결 + 조회 시 데이터 수정 제거) ===
    public TravelDto.DetailResponse getTravelDetail(Long travelId, User user) {
        // 1단계: Travel + TravelDays 조회
        Travel travel = travelRepository.findByIdWithDetails(travelId)
                .orElseThrow(() -> new ResourceNotFoundException("여행 기록", travelId));

        // 권한 검증
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 여행을 조회할 권한이 없습니다.");
        }

        // 2단계: TravelDays + Photos 조회 (별도 쿼리, 영속성 컨텍스트에 로드)
        travelRepository.findDaysWithPhotos(travelId);

        // 3단계: Photos + Comments 조회 (별도 쿼리, 영속성 컨텍스트에 로드)
        travelRepository.findPhotosWithComments(travelId);

        // 4단계: TravelLogs 조회 (별도 쿼리, 영속성 컨텍스트에 로드)
        travelRepository.findDaysWithLogs(travelId);

        // 날짜순으로 정렬 (DB 수정 없이 메모리에서만 정렬)
        List<TravelDto.TravelDayDetail> sortedDays = travel.getTravelDays().stream()
                .sorted((d1, d2) -> d1.getDate().compareTo(d2.getDate()))
                .map(this::mapToDayDetail)
                .collect(Collectors.toList());

        return TravelDto.DetailResponse.builder()
                .travelId(travel.getId())
                .title(travel.getTitle())
                .trvRegion(travel.getTrvRegion())
                .representativePhotoId(travel.getRepresentativePhotoId())
                .shareUrl(travel.getShareUrl())
                .startDate(travel.getStartDate())
                .endDate(travel.getEndDate())
                .days(sortedDays)
                .build();
    }

    // === 여행 삭제 ===
    @Transactional
    public void deleteTravel(Long travelId, User user) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행입니다."));

        // 권한 검증
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("삭제 권한이 없습니다.");
        }

        // GCS에서 사진 파일 삭제 (외부 저장소는 Cascade 안 됨)
        // N+1 개선: findByTravelDay() N번 → findByTravelDayIn() 1번으로 변경
        List<TravelDay> travelDays = travelDayRepository.findByTravelId(travelId);
        List<Photo> photos = photoRepository.findByTravelDayIn(travelDays);
        for (Photo photo : photos) {
            gcsService.deleteFile(photo.getFilePath());
            log.info("🗑️ GCS 파일 삭제: {}", photo.getFilePath());
        }

        // DB는 Cascade로 자동 삭제 (Travel -> TravelDay -> Photo, TravelLog, Cment 모두 자동)
        travelRepository.delete(travel);
        log.info("✅ 여행 삭제 완료 - Travel ID: {}", travelId);
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

        // 사진들을 명시적으로 조회 (Lazy Loading 해결)
        List<Photo> photos = photoRepository.findByTravelDay(day);

        log.info("🗑️ TravelDay 삭제 시작 - Day ID: {}, 사진 개수: {}", dayId, photos.size());

        // 일차 삭제 전에 Travel 참조 및 삭제할 dayNumber, travelId 저장
        Travel travel = day.getTravel();
        int deletedDayNumber = day.getDayNumber();
        Long travelId = travel.getId();

        // GCS에서 사진 파일 삭제 (외부 저장소는 Cascade 안 됨)
        for (Photo photo : photos) {
            gcsService.deleteFile(photo.getFilePath());
            log.info("🗑️ GCS 파일 삭제: {}", photo.getFilePath());
        }

        // 사진 먼저 DB에서 삭제 (Cascade 충돌 방지)
        photoRepository.deleteAll(photos);
        photoRepository.flush();

        // TravelDay 삭제
        travelDayRepository.delete(day);
        travelDayRepository.flush();

        log.info("✅ TravelDay 삭제 완료 - Day ID: {}", dayId);

        // 영속성 컨텍스트에서 travel 컬렉션 갱신 (삭제된 day 제거)
        travel.getTravelDays().remove(day);

        // 남은 일차들의 dayNumber 재정렬 (삭제된 일차 이후 번호들을 -1씩 당김)
        List<TravelDay> remainingDays = travelDayRepository.findByTravelId(travelId);
        remainingDays.stream()
                .filter(d -> d.getDayNumber() > deletedDayNumber)
                .forEach(d -> {
                    d.updateDayNumber(d.getDayNumber() - 1);
                    travelDayRepository.save(d);
                    log.info("🔄 일차 번호 재정렬: {} → {}", d.getDayNumber() + 1, d.getDayNumber());
                });

        // 일차 삭제 후 여행의 startDate/endDate 갱신
        updateTravelDates(travel);
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

    // === 여행 일차에 사진 추가 ===
    @Transactional
    public int addPhotosToDay(Long dayId, List<Long> photoIds, User user) {
        // TravelDay 조회
        TravelDay day = travelDayRepository.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일차입니다."));

        // 권한 검증
        if (!day.getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }

        List<Photo> addedPhotos = new ArrayList<>();

        for (Long photoId : photoIds) {
            Photo photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new IllegalArgumentException("ID " + photoId + " 사진을 찾을 수 없습니다."));

            // 사진 소유자 확인
            if (!photo.getUser().getId().equals(user.getId())) {
                throw new SecurityException("본인의 사진만 추가할 수 있습니다.");
            }

            // 사진을 해당 TravelDay에 추가
            photo.setTravelDay(day);
            addedPhotos.add(photo);
        }

        // 사진 추가 후 dayRegion 업데이트 (재조회 없이 직접 업데이트)
        updateDayRegionFromPhotos(day, addedPhotos);

        return addedPhotos.size();
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

    // === 여행 로그 삭제 (신규 추가) ===
    @Transactional
    public void deleteTravelLog(Long logId, User user) {
        TravelLog log = travelLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 일기입니다."));

        // 권한 검증
        if (!log.getTravelDay().getTravel().getUser().getId().equals(user.getId())) {
            throw new SecurityException("권한이 없습니다.");
        }

        travelLogRepository.delete(log);
    }

    // === 여행 공유 URL 조회 및 생성 (수정: String 반환) ===
    @Transactional
    public String getOrCreateShareUrl(Long travelId, User user) {
        // 여행지 존재 여부 확인
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행 기록입니다."));

        // 본인 여부 확인
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("해당 여행을 공유할 권한이 없습니다.");
        }

        // DB에 share_url이 없거나 구 도메인(travel.vercel.app)이면 새로 생성
        if (travel.getShareUrl() == null || travel.getShareUrl().isEmpty()
                || !travel.getShareUrl().startsWith(frontendBaseUrl)) {
            String uuid = UUID.randomUUID().toString();
            String fullUrl = frontendBaseUrl + "/share/" + uuid;

            // Travel 엔티티에 shareUrl 업데이트
            travel.updateShareUrl(fullUrl);
        }

        return travel.getShareUrl();
    }

    // === 공유 토큰으로 여행 조회  ===
    public TravelDto.DetailResponse getTravelByShareToken(String shareToken) {
        // shareToken을 포함하는 전체 URL 조회
        String shareUrl = frontendBaseUrl + "/share/" + shareToken;

        Travel travel = travelRepository.findByShareUrl(shareUrl)
                .orElseThrow(() -> new IllegalStateException("유효하지 않은 공유 URL입니다."));

        // TravelDays + Photos 조회
        travelRepository.findDaysWithPhotos(travel.getId());

        // Photos + Comments 조회
        travelRepository.findPhotosWithComments(travel.getId());

        // TravelLogs 조회
        travelRepository.findDaysWithLogs(travel.getId());

        // 날짜순으로 정렬
        List<TravelDto.TravelDayDetail> sortedDays = travel.getTravelDays().stream()
                .sorted((d1, d2) -> d1.getDate().compareTo(d2.getDate()))
                .map(this::mapToDayDetail)
                .collect(Collectors.toList());

        return TravelDto.DetailResponse.builder()
                .travelId(travel.getId())
                .title(travel.getTitle())
                .trvRegion(travel.getTrvRegion())
                .representativePhotoId(travel.getRepresentativePhotoId())
                .shareUrl(travel.getShareUrl())
                .startDate(travel.getStartDate())
                .endDate(travel.getEndDate())
                .days(sortedDays)
                .build();
    }

    // --- 헬퍼 메서드: TravelDay의 dayRegion 자동 설정 (개선: 추가된 사진만 고려) ---
    private void updateDayRegionFromPhotos(TravelDay day, List<Photo> photos) {
        log.info("🔍 updateDayRegion 시작 - Day {}, 추가된 사진 개수: {}", day.getDayNumber(), photos.size());

        // 위치 정보가 있는 사진들만 필터링
        List<Photo> photosWithLocation = photos.stream()
                .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                .collect(Collectors.toList());

        if (photosWithLocation.isEmpty()) {
            log.warn("⚠️ 일차 {} - 위치 정보가 있는 사진이 없음", day.getDayNumber());
            return;
        }

        // 해당 날짜의 사진들 위치 기반으로 가장 많이 등장하는 구/군 찾기
        Map<String, Long> regionCount = photosWithLocation.stream()
                .map(photo -> {
                    GeoCodingService.RegionInfo regionInfo = geoCodingService.getDetailedRegion(
                            photo.getLatitude(),
                            photo.getLongitude()
                    );
                    String region2depth = regionInfo != null ? regionInfo.getRegion2depth() : null;
                    log.info("  🗺️ Photo {} → region2depth: {}", photo.getId(), region2depth);
                    return region2depth;
                })
                .filter(region -> region != null)
                .collect(Collectors.groupingBy(
                        region -> region,
                        Collectors.counting()
                ));

        log.info("📊 regionCount: {}", regionCount);

        String dayRegion = regionCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        log.info("🎯 최종 dayRegion: {}", dayRegion);

        if (dayRegion != null) {
            day.updateDayRegion(dayRegion);
            log.info("✅ 일차 {} 지역 설정 완료: {}", day.getDayNumber(), dayRegion);
        } else {
            log.warn("⚠️ 일차 {} 지역 설정 실패 - dayRegion이 null", day.getDayNumber());
        }
    }

    // --- 헬퍼 메서드: 여행 날짜 갱신 (일차 삭제 시) ---
    private void updateTravelDates(Travel travel) {
        // Set을 List로 변환
        List<TravelDay> remainingDays = new ArrayList<>(travel.getTravelDays());

        if (remainingDays.isEmpty()) {
            // 모든 일차가 삭제된 경우 여행도 삭제하거나 날짜를 null로 설정
            log.warn("⚠️ 여행 {}의 모든 일차가 삭제됨", travel.getId());
            return;
        }

        // 남은 일차들 중 최소/최대 날짜 찾기
        LocalDate newStartDate = remainingDays.stream()
                .map(TravelDay::getDate)
                .min(LocalDate::compareTo)
                .orElse(travel.getStartDate());

        LocalDate newEndDate = remainingDays.stream()
                .map(TravelDay::getDate)
                .max(LocalDate::compareTo)
                .orElse(travel.getEndDate());

        // 날짜가 변경된 경우에만 업데이트
        if (!newStartDate.equals(travel.getStartDate()) || !newEndDate.equals(travel.getEndDate())) {
            travel.updateDates(newStartDate, newEndDate);
            log.info("📅 여행 {} 날짜 갱신: {} ~ {}", travel.getId(), newStartDate, newEndDate);
        }
    }

    // --- 헬퍼 메서드: Day 엔티티 -> DTO 변환 ---
    private TravelDto.TravelDayDetail mapToDayDetail(TravelDay day) {
        List<TravelDto.PhotoDetail> photoDetails = day.getPhotos().stream()
                .map(photo -> {
                    // 댓글 목록 변환
                    List<TravelDto.CommentDetail> commentDetails = photo.getComments().stream()
                            .map(comment -> TravelDto.CommentDetail.builder()
                                    .commentId(comment.getId())
                                    .content(comment.getContent())
                                    .createdAt(comment.getCreatedDate())
                                    .build())
                            .collect(Collectors.toList());

                    return TravelDto.PhotoDetail.builder()
                            .photoId(photo.getId())
                            .url(photo.getFilePath())
                            .takenAt(photo.getTakenAt())
                            .latitude(photo.getLatitude())
                            .longitude(photo.getLongitude())
                            .region(photo.getRegion())  // 지역 정보 추가
                            .comments(commentDetails) // 댓글 추가
                            .build();
                })
                .collect(Collectors.toList());

        TravelDto.DiaryDetail diaryDetail = null;
        if (!day.getTravelLogs().isEmpty()) {
            // Set의 첨 번째 요소 가져오기
            TravelLog log = day.getTravelLogs().stream().findFirst().orElse(null);
            if (log != null) {
                diaryDetail = TravelDto.DiaryDetail.builder()
                        .logId(log.getId())
                        .content(log.getContent())
                        .logCreated(log.getCreatedDate())
                        .build();
            }
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

    // === 여행 정보 통합 수정 (PATCH) ===
    @Transactional
    public void updateTravel(Long travelId, com.yeogidot.yeogidot.dto.TravelUpdateRequest request, User user) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("여행이 존재하지 않습니다."));

        // 권한 검증
        if (!travel.getUser().getId().equals(user.getId())) {
            throw new SecurityException("여행을 수정할 권한이 없습니다.");
        }

        // 제목 수정
        if (request.getTitle() != null) {
            travel.updateTitle(request.getTitle());
        }

        // photoIds가 제공된 경우: 증분 업데이트 (유지/삭제/추가)
        if (request.getPhotoIds() != null) {
            // null 값 필터링 (불변 리스트 방어)
            List<Long> filteredPhotoIds = request.getPhotoIds().stream()
                    .filter(id -> id != null)
                    .collect(Collectors.toList());
            request.setPhotoIds(filteredPhotoIds);
            log.info("🔄 사진 증분 업데이트 시작 - Travel ID: {}, 요청 사진 개수: {}", travelId, request.getPhotoIds().size());

            // 1단계: 기존 사진들 수집
            // N+1 개선: findByTravelDay() N번 → findByTravelDayIn()으로 IN절 1번 조회
            List<TravelDay> existingDays = travelDayRepository.findByTravelId(travelId);
            List<Photo> existingPhotos = photoRepository.findByTravelDayIn(existingDays);

            Set<Long> existingPhotoIds = existingPhotos.stream()
                    .map(Photo::getId)
                    .collect(Collectors.toSet());

            Set<Long> requestedPhotoIds = new HashSet<>(request.getPhotoIds());

            log.info("📋 기존 사진: {}, 요청 사진: {}", existingPhotoIds, requestedPhotoIds);

            // 2단계: 유지할 사진 vs 삭제할 사진 구분
            Set<Long> photosToKeep = new HashSet<>(existingPhotoIds);
            photosToKeep.retainAll(requestedPhotoIds); // 교집합 (유지)

            Set<Long> photosToDelete = new HashSet<>(existingPhotoIds);
            photosToDelete.removeAll(requestedPhotoIds); // 기존에만 있음 (삭제)

            Set<Long> photosToAdd = new HashSet<>(requestedPhotoIds);
            photosToAdd.removeAll(existingPhotoIds); // 요청에만 있음 (추가)

            log.info("✅ 유지: {}, 🗑️ 삭제: {}, ➕ 추가: {}", photosToKeep, photosToDelete, photosToAdd);

            // 3단계: 삭제할 사진 처리 (GCS + DB)
            if (!photosToDelete.isEmpty()) {
                for (Long photoId : photosToDelete) {
                    Photo photo = photoRepository.findById(photoId).orElse(null);
                    if (photo != null) {
                        try {
                            // GCS 파일 삭제
                            gcsService.deleteFile(photo.getFilePath());
                            log.info("🗑️ GCS 파일 삭제: {}", photo.getFilePath());

                            // DB에서 사진 삭제 (TravelDay 연결 해제)
                            photo.setTravelDay(null);
                            photoRepository.delete(photo);
                            log.info("🗑️ DB 사진 삭제: Photo ID {}", photoId);
                        } catch (Exception e) {
                            log.warn("⚠️ 사진 삭제 실패 (계속 진행): Photo ID {}", photoId, e);
                        }
                    }
                }
            }

            // 4단계: 추가할 사진 검증 및 수집
            List<Photo> allPhotos = new ArrayList<>();

            // 유지할 사진 추가
            for (Long photoId : photosToKeep) {
                Photo photo = photoRepository.findById(photoId).orElse(null);
                if (photo != null && photo.getTakenAt() != null) {
                    allPhotos.add(photo);
                }
            }

            // 새로운 사진 검증 및 추가
            for (Long photoId : photosToAdd) {
                Photo photo = photoRepository.findById(photoId)
                        .orElseThrow(() -> new IllegalArgumentException("사진 ID " + photoId + "를 찾을 수 없습니다."));

                // 사진 소유권 검증
                if (!photo.getUser().getId().equals(user.getId())) {
                    throw new SecurityException("본인의 사진만 추가할 수 있습니다. 사진 ID: " + photoId);
                }

                // 촬영 날짜 검증
                if (photo.getTakenAt() == null) {
                    throw new IllegalArgumentException("사진 ID " + photoId + "에 촬영 날짜 정보가 없습니다.");
                }

                allPhotos.add(photo);
            }

            // 5단계: 빈 일차 삭제
            for (TravelDay day : existingDays) {
                List<Photo> remainingPhotos = photoRepository.findByTravelDay(day);
                if (remainingPhotos.isEmpty()) {
                    log.info("🗑️ 빈 일차 삭제: Day {} ({})", day.getDayNumber(), day.getDate());
                    travelDayRepository.delete(day);
                }
            }
            travelDayRepository.flush();

            // 6단계: 사진 날짜별로 그룹화 및 일차 재구성
            if (!allPhotos.isEmpty()) {
                List<LocalDate> photoDates = allPhotos.stream()
                        .map(photo -> photo.getTakenAt().toLocalDate())
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());

                log.info("📅 최종 사진 날짜들: {}", photoDates);

                // 기존 일차를 날짜 맵으로 구성 (재사용)
                Map<LocalDate, TravelDay> dayMap = new HashMap<>();
                List<TravelDay> currentDays = travelDayRepository.findByTravelId(travelId);

                for (TravelDay day : currentDays) {
                    dayMap.put(day.getDate(), day);
                }

                // 7단계: 필요한 일차 생성 (없는 날짜만)
                for (LocalDate photoDate : photoDates) {
                    if (!dayMap.containsKey(photoDate)) {
                        TravelDay newDay = TravelDay.builder()
                                .travel(travel)
                                .dayNumber(0) // 임시, 나중에 재정렬
                                .date(photoDate)
                                .build();
                        travelDayRepository.save(newDay);
                        dayMap.put(photoDate, newDay);
                        log.info("➕ 새 일차 생성: {}", photoDate);
                    }
                }

                // 8단계: dayNumber 재정렬
                List<TravelDay> sortedDays = dayMap.values().stream()
                        .sorted(Comparator.comparing(TravelDay::getDate))
                        .collect(Collectors.toList());

                int dayNumber = 1;
                for (TravelDay day : sortedDays) {
                    day.updateDayNumber(dayNumber++);
                    travelDayRepository.save(day);
                }

                // 9단계: 사진을 해당 날짜의 TravelDay에 배치
                for (Photo photo : allPhotos) {
                    LocalDate photoDate = photo.getTakenAt().toLocalDate();
                    TravelDay matchingDay = dayMap.get(photoDate);

                    if (matchingDay != null) {
                        photo.setTravelDay(matchingDay);
                        photoRepository.save(photo);
                        log.info("📸 사진 {} → Day {} 연결", photo.getId(), matchingDay.getDayNumber());
                    }
                }

                // 10단계: 각 TravelDay의 dayRegion 자동 설정
                for (LocalDate photoDate : photoDates) {
                    TravelDay day = dayMap.get(photoDate);

                    // 해당 날짜의 사진들 수집
                    List<Photo> dayPhotos = allPhotos.stream()
                            .filter(p -> p.getTakenAt().toLocalDate().equals(photoDate))
                            .collect(Collectors.toList());

                    // 사진 위치 기반으로 dayRegion 결정
                    Map<String, Long> regionCount = dayPhotos.stream()
                            .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                            .map(photo -> {
                                GeoCodingService.RegionInfo regionInfo = geoCodingService.getDetailedRegion(
                                        photo.getLatitude(),
                                        photo.getLongitude()
                                );
                                return regionInfo != null ? regionInfo.getRegion2depth() : null;
                            })
                            .filter(region -> region != null)
                            .collect(Collectors.groupingBy(
                                    region -> region,
                                    Collectors.counting()
                            ));

                    String dayRegion = regionCount.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(null);

                    if (dayRegion != null) {
                        day.updateDayRegion(dayRegion);
                        travelDayRepository.save(day);
                        log.info("🗺️ 일차 {} 지역 설정: {}", day.getDayNumber(), dayRegion);
                    }
                }

                // 11단계: Travel의 startDate, endDate 갱신
                LocalDate newStartDate = photoDates.get(0);
                LocalDate newEndDate = photoDates.get(photoDates.size() - 1);
                travel.updateDates(newStartDate, newEndDate);
                log.info("📅 여행 날짜 갱신: {} ~ {}", newStartDate, newEndDate);

                // 12단계: 지역명 자동 갱신 (위도/경도 기반)
                Map<String, Long> travelRegionCount = allPhotos.stream()
                        .filter(photo -> photo.getLatitude() != null && photo.getLongitude() != null)
                        .map(photo -> geoCodingService.getRegionFromCoordinates(
                                photo.getLatitude(),
                                photo.getLongitude()
                        ))
                        .filter(region -> region != null)
                        .collect(Collectors.groupingBy(
                                region -> region,
                                Collectors.counting()
                        ));

                String newTrvRegion = travelRegionCount.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("미지정");

                travel.updateTrvRegion(newTrvRegion);
                log.info("🗺️ 여행 지역 갱신: {}", newTrvRegion);
            } else {
                log.warn("⚠️ 모든 사진이 삭제되었습니다. 여행 ID: {}", travelId);
            }

            log.info("✅ 사진 증분 업데이트 완료 - Travel ID: {}", travelId);
        }

        // 대표 사진 수정 (photoIds 처리 후 실행하여 유효한 사진만 설정)
        if (request.getRepresentativePhotoId() != null) {
            // 사진 존재 여부 및 소유권 확인
            Photo repPhoto = photoRepository.findById(request.getRepresentativePhotoId())
                    .orElseThrow(() -> new IllegalArgumentException("대표 사진이 존재하지 않습니다."));

            // 소유권 검증
            if (!repPhoto.getUser().getId().equals(user.getId())) {
                throw new SecurityException("본인의 사진만 대표 사진으로 설정할 수 있습니다.");
            }

            // 해당 사진이 이 여행에 속하는지 검증 (photoIds로 교체한 경우 포함)
            if (repPhoto.getTravelDay() == null ||
                    !repPhoto.getTravelDay().getTravel().getId().equals(travelId)) {
                throw new IllegalArgumentException("이 여행에 속하지 않는 사진은 대표 사진으로 설정할 수 없습니다.");
            }

            travel.updateRepresentativePhoto(request.getRepresentativePhotoId());
            log.info("🖼️ 대표 사진 변경: {}", request.getRepresentativePhotoId());
        }
    }
}