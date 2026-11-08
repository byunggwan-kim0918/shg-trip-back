package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.WithdrawnIdentityHasher;
import com.shg.trip.shgtrip.domain.auth.service.oauth.KakaoUnlinkClient;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    public static final String CONFIRM_PHRASE = "탈퇴";

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
    private final KakaoUnlinkClient kakaoUnlinkClient;

    /**
     * 개인정보와 일정을 지우고 세션을 끊는다. 이미 탈퇴한 사용자면 아무것도 하지 않는다.
     * Redis 정리와 제공자 연결 해제는 커밋 뒤에 하므로 DB 롤백 시 계정이 반쯤 끊기지 않는다.
     */
    @Transactional
    public void withdraw(Long userId, String confirm) {
        if (!CONFIRM_PHRASE.equals(confirm == null ? null : confirm.trim())) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_CONFIRM_MISMATCH);
        }
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
        List<String> kakaoIds = providers.stream()
                .filter(p -> p.getProvider() == OAuthProvider.KAKAO)
                .map(UserAuthProvider::getProviderId)
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
                cleanUpAfterCommit(userId, identityHashes, kakaoIds);
            }
        });
        log.info("회원 탈퇴 userId={} providers={} itineraries={}",
                userId, providers.stream().map(UserAuthProvider::getProvider).toList(), itineraries);
    }

    private void cleanUpAfterCommit(Long userId, List<String> identityHashes, List<String> kakaoIds) {
        runQuietly("탈퇴 표시", () -> withdrawnUserRegistry.mark(userId));
        runQuietly("refresh token 삭제", () -> refreshTokenRepository.deleteByUserId(userId));
        generationPolicyService.parkCountersForWithdrawal(userId, identityHashes);
        kakaoIds.forEach(kakaoUnlinkClient::unlink);
    }

    private void runQuietly(String label, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("탈퇴 후 정리 실패({}): {}", label, e.getMessage(), e);
        }
    }
}
