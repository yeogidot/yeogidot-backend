package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.*;
import com.yeogidot.yeogidot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다. ID=" + travelId));

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

        // 1단계: 여행에 속한 모든 사진 삭제 (GCS + DB)
        List<TravelDay> travelDays = travelDayRepository.findByTravelId(travelId);
        for (TravelDay day : travelDays) {
            List<Photo> photos = photoRepository.findByTravelDay(day);
            for (Photo photo : photos) {
                // GCS에서 파일 삭제
                gcsService.deleteFile(photo.getFilePath());
                // DB에서 사진 삭제
                photoRepository.delete(photo);
            }
        }

        // 2단계: 여행 로그 삭제
        for (TravelDay day : travelDays) {
            travelLogRepository.deleteByTravelDay(day);
        }

        // 3단계: TravelDay 삭제
        travelDayRepository.deleteAll(travelDays);

        // 4단계: Travel 삭제
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
        
        // 일차 삭제 후 여행의 startDate/endDate 갱신
        Travel travel = day.getTravel();
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

    // === 여행 일차에 사진 추가 (개선: 불필요한 재조회 제거) ===
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

        return travel.getShareUrl();
    }

    // === 공유 토큰으로 여행 조회 (신규 추가) ===
    public TravelDto.DetailResponse getTravelByShareToken(String shareToken) {
        // shareToken을 포함하는 전체 URL 조회
        String shareUrl = "https://travel.vercel.app/share/" + shareToken;
        
        Travel travel = travelRepository.findByShareUrl(shareUrl)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 공유 URL입니다."));

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
}
