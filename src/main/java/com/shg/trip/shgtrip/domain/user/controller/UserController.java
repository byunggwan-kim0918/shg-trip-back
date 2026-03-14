package com.shg.trip.shgtrip.domain.user.controller;

import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;
import com.shg.trip.shgtrip.domain.user.dto.ProfileUpdateRequest;
import com.shg.trip.shgtrip.domain.user.service.UserService;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ProfileResponse>> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        ProfileResponse profile = userService.getProfile(principal.id());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PatchMapping("/profile")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ProfileUpdateRequest request) {
        ProfileResponse profile = userService.updateProfile(principal.id(), request);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }
}
