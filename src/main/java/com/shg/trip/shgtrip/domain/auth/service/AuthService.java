package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.dto.*;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.RefreshToken;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthProviderStrategy;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthStrategyFactory;
import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;
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
@Transactional(readOnly = true)
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
            provider = OAuthProvider.valueOf(request.provider().toUpperCase());
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

        // Redis는 JPA 트랜잭션과 독립적으로 즉시 반영됨.
        // JPA 커밋 실패 시 고아 토큰이 남을 수 있으나, TTL(7일)로 자연 만료되고
        // 실제 사용 시 userId 검증에서 걸러지므로 허용 가능한 수준.
        try {
            refreshTokenRepository.save(RefreshToken.builder()
                    .token(refreshToken)
                    .userId(user.getId())
                    .revoked(false)
                    .expiration(refreshExpirationMs)
                    .build());
        } catch (Exception e) {
            log.error("Redis refresh token 저장 실패. userId={}, error={}", user.getId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return new OAuthLoginResult(accessToken, refreshToken, isNewUser, refreshExpirationMs, ProfileResponse.from(user));
    }

    @Transactional
    public OAuthLoginResult refreshAccessToken(String refreshTokenValue) {
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

        // Rotation: 새 토큰 저장 성공 확인 후 기존 토큰 revoke
        // 순서 보장: 새 토큰 save 실패 시 기존 토큰이 살아있어 재시도 가능
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String newRefreshToken = jwtTokenProvider.createRefreshToken();
        long refreshExpirationMs = jwtTokenProvider.getRefreshExpiration();

        try {
            refreshTokenRepository.save(RefreshToken.builder()
                    .token(newRefreshToken)
                    .userId(user.getId())
                    .revoked(false)
                    .expiration(refreshExpirationMs)
                    .build());
        } catch (Exception e) {
            log.error("Redis refresh token 저장 실패 (rotation). userId={}, error={}", user.getId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        // 새 토큰 저장 성공 후 기존 토큰 revoke
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        return new OAuthLoginResult(newAccessToken, newRefreshToken, false, refreshExpirationMs, ProfileResponse.from(user));
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }
}
