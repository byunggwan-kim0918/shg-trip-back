package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthSession;
import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.WithdrawnIdentityHasher;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthProviderStrategy;
import com.shg.trip.shgtrip.domain.auth.service.oauth.OAuthStrategyFactory;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryEditEventRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryStepRepository;
import com.shg.trip.shgtrip.domain.planning.service.GenerationPolicyService;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.security.WithdrawnUserRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final UserAuthProviderRepository authProviderRepository;
    private final ItineraryRepository itineraryRepository;
    private final ItineraryStepRepository itineraryStepRepository;
    private final ItineraryEditEventRepository itineraryEditEventRepository;
    private final GenerationTraceRepository generationTraceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WithdrawnUserRegistry withdrawnUserRegistry;
    private final WithdrawnIdentityHasher identityHasher;
    private final GenerationPolicyService generationPolicyService;
    private final TravelPlannerService travelPlannerService;
    private final OAuthStrategyFactory strategyFactory;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redisTemplate;

    // 요청마다 제공자 토큰 API를 부르므로 한 사용자가 반복 호출해 앱의 제공자 호출 한도를 쓰지 못하게 한다.
    private static final String RATE_KEY_PREFIX = "withdraw:rate:";
    static final int MAX_ATTEMPTS_PER_MINUTE = 5;

    /**
     * 탈퇴 직전 소셜 재로그인으로 받은 code로 본인을 확인한 뒤 개인정보와 일정을 지우고 세션·제공자 연결을 끊는다.
     * 제공자 호출은 DB 잠금을 잡기 전에 끝내고, Redis 정리와 연결 해제는 커밋 뒤에 해 롤백 시 계정이 반쯤 끊기지 않는다.
     */
    public void withdraw(Long userId, OAuthProvider provider, String code) {
        checkRateLimit(userId);
        OAuthProviderStrategy strategy = strategyFactory.getStrategy(provider);
        OAuthSession session;
        try {
            session = strategy.authenticate(code);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.REAUTH_FAILED);
        }
        Optional<UserAuthProvider> linked =
                authProviderRepository.findByProviderAndProviderId(provider, session.userInfo().providerId());
        if (linked.isEmpty()) {
            // 미가입 계정은 재로그인 동의로 앱 연결이 막 생겼으므로 그 계정 주인의 토큰으로 바로 끊는다.
            strategy.revoke(session.accessToken());
            log.warn("탈퇴 재인증 계정 불일치 userId={} provider={} 미가입 계정", userId, provider);
            throw new BusinessException(ErrorCode.REAUTH_MISMATCH);
        }
        // 다른 회원 계정의 토큰이므로 연결 해제에도 쓰지 않는다.
        if (!linked.get().getUserId().equals(userId)) {
            log.warn("탈퇴 재인증 계정 불일치 userId={} provider={} 다른 회원 계정", userId, provider);
            throw new BusinessException(ErrorCode.REAUTH_MISMATCH);
        }
        transactionTemplate.executeWithoutResult(status ->
                deleteAccount(userId, () -> strategy.revoke(session.accessToken())));
    }

    private void deleteAccount(Long userId, Runnable revokeProvider) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.isWithdrawn()) {
            return;
        }
        // 진행 중 생성이 일정 삭제 뒤에 저장되면 탈퇴 계정에 일정이 남는다.
        runQuietly("생성 작업 취소", () -> travelPlannerService.cancelActiveJob(userId));

        List<UserAuthProvider> providers = authProviderRepository.findByUserId(userId);
        List<String> identityHashes = providers.stream()
                .flatMap(p -> identityHasher.hash(p.getProvider(), p.getProviderId()).stream())
                .toList();

        // 자식 테이블 FK가 대부분 없어 DB가 연쇄 삭제하지 않으므로 자식부터 지운다.
        itineraryStepRepository.deleteAlternativesByUserId(userId);
        itineraryStepRepository.deleteAllByUserId(userId);
        generationTraceRepository.deleteByUserId(userId);
        itineraryEditEventRepository.deleteAllByUserId(userId);
        int itineraries = itineraryRepository.deleteAllByUserId(userId);
        authProviderRepository.deleteAll(providers);
        user.withdraw();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanUpAfterCommit(userId, identityHashes, revokeProvider);
            }
        });
        log.info("회원 탈퇴 userId={} providers={} itineraries={}",
                userId, providers.stream().map(UserAuthProvider::getProvider).toList(), itineraries);
    }

    private void cleanUpAfterCommit(Long userId, List<String> identityHashes, Runnable revokeProvider) {
        runQuietly("탈퇴 표시", () -> withdrawnUserRegistry.mark(userId));
        runQuietly("refresh token 삭제", () -> refreshTokenRepository.deleteByUserId(userId));
        generationPolicyService.parkCountersForWithdrawal(userId, identityHashes);
        runQuietly("제공자 연결 해제", revokeProvider);
    }

    /** Redis 장애 시 통과시킨다. 탈퇴를 막는 쪽 피해가 더 크다. */
    private void checkRateLimit(Long userId) {
        long attempts;
        try {
            String key = RATE_KEY_PREFIX + userId;
            redisTemplate.opsForValue().setIfAbsent(key, "0", Duration.ofMinutes(1));
            Long count = redisTemplate.opsForValue().increment(key);
            // 시드와 INCR 사이에 키가 만료되면 TTL 없는 키가 생겨 영구 차단된다.
            Long ttl = redisTemplate.getExpire(key);
            if (ttl != null && ttl == -1) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            attempts = count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("탈퇴 요청 제한 조회 실패(fail-open, user {}): {}", userId, e.getMessage());
            return;
        }
        if (attempts > MAX_ATTEMPTS_PER_MINUTE) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_RATE_LIMITED);
        }
    }

    private void runQuietly(String label, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("탈퇴 후 정리 실패({}): {}", label, e.getMessage(), e);
        }
    }
}
