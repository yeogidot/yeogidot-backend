package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.TravelDto;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.TravelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/travels")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;
    private final UserRepository userRepository;

    // 내 여행 목록 조회 (GET /api/travels)
    @GetMapping
    public ResponseEntity<List<TravelDto>> getMyTravels() {
        User user = getCurrentUser();
        return ResponseEntity.ok(travelService.getMyTravels(user));
    }

    // 내 여행 삭제 (DELETE /api/travels/{travelId})
    @DeleteMapping("/{travelId}")
    public ResponseEntity<Void> deleteTravel(@PathVariable Long travelId) {
        User user = getCurrentUser();
        travelService.deleteTravel(travelId, user);
        return ResponseEntity.noContent().build(); // 204 No Content
    }

    // (편의용) 현재 로그인 유저 가져오는 메소드
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저 정보 없음"));
    }
}

