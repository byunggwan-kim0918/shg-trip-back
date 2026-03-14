package com.shg.trip.shgtrip.domain.auth.controller;

import com.shg.trip.shgtrip.domain.auth.dto.*;
import com.shg.trip.shgtrip.domain.auth.service.AuthService;
import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/oauth/callback")
    public ResponseEntity<ApiResponse<OAuthCallbackResponse>> oauthCallback(
            @Valid @RequestBody OAuthCallbackRequest request,
            HttpServletResponse response) {

        OAuthLoginResult result = authService.processOAuthCallback(request);

        addRefreshTokenCookie(response, result.refreshToken());

        OAuthCallbackResponse body = new OAuthCallbackResponse(
                result.accessToken(),
                result.isNewUser(),
                ProfileResponse.from(result.user())
        );

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @CookieValue(name = "refresh_token") String refreshToken,
            HttpServletResponse response) {

        TokenRefreshResult result = authService.refreshAccessToken(refreshToken);

        addRefreshTokenCookie(response, result.refreshToken());

        return ResponseEntity.ok(ApiResponse.success(
                new TokenRefreshResponse(result.accessToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {

        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        clearRefreshTokenCookie(response);

        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtTokenProvider.getRefreshExpiration() / 1000)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
