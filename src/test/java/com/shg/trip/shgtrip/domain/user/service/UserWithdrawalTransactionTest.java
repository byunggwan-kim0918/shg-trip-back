package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthSession;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
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
import com.shg.trip.shgtrip.global.security.WithdrawnUserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 실제 트랜잭션 동기화로 커밋 뒤 정리가 롤백 때 실행되지 않는지 본다. */
@ExtendWith(MockitoExtension.class)
class UserWithdrawalTransactionTest {

    @Mock private UserRepository userRepository;
    @Mock private UserAuthProviderRepository authProviderRepository;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private ItineraryStepRepository itineraryStepRepository;
    @Mock private ItineraryEditEventRepository itineraryEditEventRepository;
    @Mock private GenerationTraceRepository generationTraceRepository;
    @Mock private TripChatSessionRepository tripChatSessionRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private WithdrawnUserRegistry withdrawnUserRegistry;
    @Mock private WithdrawnIdentityHasher identityHasher;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private TravelPlannerService travelPlannerService;
    @Mock private OAuthStrategyFactory strategyFactory;
    @Mock private OAuthProviderStrategy strategy;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new UserWithdrawalService(userRepository, authProviderRepository, itineraryRepository,
                itineraryStepRepository, itineraryEditEventRepository, generationTraceRepository,
                tripChatSessionRepository, refreshTokenRepository, withdrawnUserRegistry, identityHasher, generationPolicyService,
                travelPlannerService, strategyFactory, new TransactionTemplate(new NoResourceTransactionManager()),
                redisTemplate);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        User user = User.builder().email("a@b.c").nickname("닉").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        UserAuthProvider linked = UserAuthProvider.builder()
                .userId(1L).provider(OAuthProvider.KAKAO).providerId("k-1").build();
        given(strategyFactory.getStrategy(OAuthProvider.KAKAO)).willReturn(strategy);
        given(strategy.authenticate("code")).willReturn(
                new OAuthSession(new OAuthUserInfo("k-1", null, null, null), "provider-token"));
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "k-1"))
                .willReturn(Optional.of(linked));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of(linked));
    }

    @Test
    void 커밋되면_연결_해제와_세션_정리를_한다() {
        service.withdraw(1L, OAuthProvider.KAKAO, "code");

        verify(strategy).revoke("provider-token");
        verify(withdrawnUserRegistry).mark(1L);
        verify(refreshTokenRepository).deleteByUserId(1L);
    }

    @Test
    void 삭제_중_실패해_롤백되면_연결_해제와_세션_정리를_하지_않는다() {
        given(itineraryRepository.deleteAllByUserId(1L)).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.withdraw(1L, OAuthProvider.KAKAO, "code"))
                .isInstanceOf(IllegalStateException.class);

        verify(strategy, never()).revoke(anyString());
        verify(withdrawnUserRegistry, never()).mark(anyLong());
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());
    }

    /** DB 없이 커밋·롤백과 동기화 콜백 순서만 실제와 같게 돌린다. */
    private static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
