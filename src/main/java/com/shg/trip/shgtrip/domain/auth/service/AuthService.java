package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.dto.*;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.RefreshToken;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthProviderStrategy;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthStrategyFactory;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OAuthStrategyFactory oAuthStrategyFactory;
    private final UserRepository userRepository;
    private final UserAuthProviderRepository authProviderRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public OAuthLoginResult processOAuthCallback(OAuthCallbackRequest request) {
        OAuthProvider provider;
        try {
            provider = OAuthProvider.valueOf(request.provider());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PROVIDER);
        }

        OAuthProviderStrategy strategy = oAuthStrategyFactory.getStrategy(provider);
        OAuthUserInfo userInfo = strategy.getUserInfo(request.code());

        Optional<UserAuthProvider> existingAuth = authProviderRepository
                .findByProviderAndProviderId(provider, userInfo.providerId());

        User user;
        boolean isNewUser;

        if (existingAuth.isPresent()) {
            user = userRepository.findById(existingAuth.get().getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            isNewUser = user.isNewUser();
        } else {
            user = User.builder()
                    .email(userInfo.email())
                    .nickname(null)
                    .profileImage(userInfo.profileImage())
                    .build();
            user = userRepository.save(user);
            isNewUser = true;

            UserAuthProvider authProvider = UserAuthProvider.builder()
                    .userId(user.getId())
                    .provider(provider)
                    .providerId(userInfo.providerId())
                    .build();
            authProviderRepository.save(authProvider);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken();

        long refreshExpirationMs = jwtTokenProvider.getRefreshExpiration();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(refreshToken)
                .userId(user.getId())
                .revoked(false)
                .expiration(refreshExpirationMs)
                .build());

        return new OAuthLoginResult(accessToken, refreshToken, isNewUser, user);
    }

    public TokenRefreshResult refreshAccessToken(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        // AUTH_007: revoked된 토큰으로 접근 → 탈취 감지 → 해당 유저의 전체 세션 무효화
        if (refreshToken.isRevoked()) {
            log.warn("Refresh Token 재사용 감지 (탈취 의심). userId={}", refreshToken.getUserId());
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }

        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // Rotation: 기존 토큰 revoke 후 저장 + 새 토큰 발급
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String newRefreshToken = jwtTokenProvider.createRefreshToken();

        long refreshExpirationMs = jwtTokenProvider.getRefreshExpiration();
        refreshTokenRepository.save(RefreshToken.builder()
                .token(newRefreshToken)
                .userId(user.getId())
                .revoked(false)
                .expiration(refreshExpirationMs)
                .build());

        return new TokenRefreshResult(newAccessToken, newRefreshToken);
    }

    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }
}
