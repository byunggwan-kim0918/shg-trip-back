package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.dto.*;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.RefreshToken;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthProviderStrategy;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthStrategyFactory;
import com.shg.trip.shgtrip.domain.planning.service.GenerationPolicyService;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final WithdrawnIdentityHasher identityHasher;
    private final GenerationPolicyService generationPolicyService;

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
            if (user.isWithdrawn()) {
                throw new BusinessException(ErrorCode.USER_WITHDRAWN);
            }
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
            Long newUserId = user.getId();
            identityHasher.hash(provider, userInfo.providerId()).ifPresent(hash -> restoreCountersAfterCommit(hash, newUserId));
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken();
        long refreshExpirationMs = jwtTokenProvider.getRefreshExpiration();

        // Redis 저장은 JPA 트랜잭션 밖이라 커밋이 실패해도 남은 토큰은 TTL(7일)로 만료된다.
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
            // 유예 밖의 재사용은 탈취로 보고 해당 유저의 전체 세션을 무효화한다.
            log.warn("Refresh Token 재사용 감지 (탈취 의심). userId={}", refreshToken.getUserId());
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }

        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.isWithdrawn()) {
            refreshTokenRepository.deleteByUserId(user.getId());
            throw new BusinessException(ErrorCode.USER_WITHDRAWN);
        }

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
     * 동시에 보낸 refresh 중 첫 요청만 회전에 성공하므로, 나머지를 탈취로 보면 정상 사용자가 로그아웃된다.
     * 유예 안이면 이미 발급된 새 refresh 토큰을 새로 회전하지 않고 돌려준다.
     */
    private Optional<OAuthLoginResult> reuseWithinGrace(RefreshToken revoked) {
        if (revoked.getReplacedBy() == null
                || System.currentTimeMillis() - revoked.getRevokedAt() > REUSE_GRACE_MS) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByToken(revoked.getReplacedBy())
                .filter(next -> !next.isRevoked())
                .flatMap(next -> userRepository.findById(next.getUserId()))
                .filter(user -> !user.isWithdrawn())
                .map(user -> new OAuthLoginResult(
                        jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name()),
                        revoked.getReplacedBy(), false, jwtTokenProvider.getRefreshExpiration(),
                        ProfileResponse.from(user)));
    }

    /** 가입이 롤백되면 카운터가 존재하지 않는 사용자로 옮겨져 사라진다. */
    private void restoreCountersAfterCommit(String identityHash, Long newUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            generationPolicyService.restoreCountersOnSignup(identityHash, newUserId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                generationPolicyService.restoreCountersOnSignup(identityHash, newUserId);
            }
        });
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }
}
