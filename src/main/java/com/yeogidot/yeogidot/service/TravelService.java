package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TravelService {

    private final TravelRepository travelRepository;
    private final PhotoRepository photoRepository; // 대표 사진 URL을 찾기 위해 필요

    // 1. 내 여행 목록 조회
    @Transactional(readOnly = true)
    public List<TravelDto> getMyTravels(User user) {
        // 내 여행 다 가져오기 (최신순)
        List<Travel> travels = travelRepository.findAllByUserOrderByTravelIdDesc(user);

        // Entity -> DTO 변환
        return travels.stream().map(travel -> {
            String photoUrl = null;

            // [★ 핵심 로직] 대표 사진 ID가 있으면 -> Photo 테이블에서 진짜 URL 찾아오기
            if (travel.getRepresentativePhotoId() != null) {
                photoUrl = photoRepository.findById(travel.getRepresentativePhotoId())
                        .map(Photo::getUrl) // Photo 엔티티의 getUrl() 사용
                        .orElse(null);
            }

            return TravelDto.builder()
                    .travelId(travel.getTravelId())
                    .title(travel.getTitle())
                    // 이제 엔티티에 필드가 있으니 주석 해제!
                    .trvRegion(travel.getTrvRegion())
                    .startDate(travel.getStartDate())
                    .endDate(travel.getEndDate())
                    // 찾아온 URL을 DTO에 넣기
                    .representativeImageUrl(photoUrl)
                    .build();
        }).collect(Collectors.toList());
    }

    // 2. 여행 삭제
    @Transactional
    public void deleteTravel(Long travelId, User user) {
        Travel travel = travelRepository.findById(travelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 여행입니다."));

        // 내 여행인지 확인 (남의 거 못 지우게)
        if (!travel.getUser().getUserId().equals(user.getUserId())) {
            throw new IllegalArgumentException("삭제 권한이 없습니다.");
        }

        travelRepository.delete(travel);
    }
}