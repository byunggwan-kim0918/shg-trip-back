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

    private static final long REUSE_GRACE_MS = 10_000;

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
                    .nickname(userInfo.nickname())
                    .profileImage(userInfo.profileImage())
                    .build();
            user = userRepository.save(user);
            isNewUser = user.isNewUser();

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

        // Redis TTL(7일)로 자연 만료되므로 JPA 커밋 실패 시에도 허용 가능
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

        if (refreshToken.isRevoked()) {
            Optional<OAuthLoginResult> concurrent = reuseWithinGrace(refreshToken);
            if (concurrent.isPresent()) return concurrent.get();
            // 유예 밖의 재사용 → 탈취로 보고 해당 유저의 전체 세션 무효화
            log.warn("Refresh Token 재사용 감지 (탈취 의심). userId={}", refreshToken.getUserId());
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }

        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // Rotation: 새 토큰 저장 후 기존 토큰 revoke
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

        refreshToken.revoke(newRefreshToken);
        refreshTokenRepository.save(refreshToken);

        return new OAuthLoginResult(newAccessToken, newRefreshToken, false, refreshExpirationMs, ProfileResponse.from(user));
    }

    /**
     * 회전 직후 같은 토큰으로 들어온 요청. 탭 하나가 동시에 보낸 API 호출들이 각자 refresh하면
     * 첫 요청만 회전에 성공하고 나머지는 revoked 토큰을 들고 온다 — 이를 탈취로 보면 정상 사용자가
     * 로그아웃된다. 유예 안이면 이미 발급된 새 refresh 토큰을 그대로 돌려준다(새로 회전하지 않는다).
     */
    private Optional<OAuthLoginResult> reuseWithinGrace(RefreshToken revoked) {
        if (revoked.getReplacedBy() == null
                || System.currentTimeMillis() - revoked.getRevokedAt() > REUSE_GRACE_MS) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByToken(revoked.getReplacedBy())
                .filter(next -> !next.isRevoked())
                .flatMap(next -> userRepository.findById(next.getUserId()))
                .map(user -> new OAuthLoginResult(
                        jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name()),
                        revoked.getReplacedBy(), false, jwtTokenProvider.getRefreshExpiration(),
                        ProfileResponse.from(user)));
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }
}
