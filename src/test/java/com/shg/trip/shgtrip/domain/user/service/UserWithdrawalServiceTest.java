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
import com.shg.trip.shgtrip.domain.user.entity.UserStatus;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.security.WithdrawnUserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @InjectMocks private UserWithdrawalService service;

    @Mock private UserRepository userRepository;
    @Mock private UserAuthProviderRepository authProviderRepository;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private ItineraryStepRepository itineraryStepRepository;
    @Mock private ItineraryEditEventRepository itineraryEditEventRepository;
    @Mock private GenerationTraceRepository generationTraceRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private WithdrawnUserRegistry withdrawnUserRegistry;
    @Mock private WithdrawnIdentityHasher identityHasher;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private TravelPlannerService travelPlannerService;
    @Mock private OAuthStrategyFactory strategyFactory;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private OAuthProviderStrategy strategy;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private User user;
    private UserAuthProvider linked;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        user = User.builder().email("a@b.c").nickname("닉네임").profileImage("https://img").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        linked = UserAuthProvider.builder().userId(1L).provider(OAuthProvider.NAVER).providerId("n-1").build();
        lenient().when(strategyFactory.getStrategy(OAuthProvider.NAVER)).thenReturn(strategy);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        lenient().doAnswer(inv -> {
            inv.<Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void 같은_계정으로_재인증하면_지우고_커밋_후_연결을_해제한다() {
        givenReauthAs("n-1");
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "n-1"))
                .willReturn(Optional.of(linked));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of(linked));
        given(identityHasher.hash(OAuthProvider.NAVER, "n-1")).willReturn(Optional.of("h"));

        service.withdraw(1L, OAuthProvider.NAVER, "code");

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getEmail()).isEqualTo("withdrawn-1@invalid");
        assertThat(user.getNickname()).isNull();
        InOrder deletes = inOrder(itineraryStepRepository, generationTraceRepository,
                itineraryEditEventRepository, itineraryRepository);
        deletes.verify(itineraryStepRepository).deleteAlternativesByUserId(1L);
        deletes.verify(itineraryStepRepository).deleteAllByUserId(1L);
        deletes.verify(generationTraceRepository).deleteByUserId(1L);
        deletes.verify(itineraryEditEventRepository).deleteAllByUserId(1L);
        deletes.verify(itineraryRepository).deleteAllByUserId(1L);
        verify(authProviderRepository).deleteAll(List.of(linked));
        verify(strategy, never()).revoke(anyString());

        commit();

        verify(withdrawnUserRegistry).mark(1L);
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(generationPolicyService).parkCountersForWithdrawal(1L, List.of("h"));
        verify(strategy).revoke("provider-token");
    }

    @Test
    void 다른_계정으로_재인증하면_아무것도_지우지_않고_토큰도_쓰지_않는다() {
        givenReauthAs("someone-else");
        UserAuthProvider other = UserAuthProvider.builder()
                .userId(2L).provider(OAuthProvider.NAVER).providerId("someone-else").build();
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "someone-else"))
                .willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.withdraw(1L, OAuthProvider.NAVER, "code"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.REAUTH_MISMATCH.getMessage());
        verifyNoInteractions(transactionTemplate, userRepository, itineraryRepository);
        verify(strategy, never()).revoke(anyString());
    }

    @Test
    void 가입되지_않은_소셜_계정으로_재인증하면_거부하고_방금_생긴_연결은_끊는다() {
        givenReauthAs("unknown");
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "unknown"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(1L, OAuthProvider.NAVER, "code"))
                .hasMessageContaining(ErrorCode.REAUTH_MISMATCH.getMessage());
        verifyNoInteractions(transactionTemplate);
        verify(strategy).revoke("provider-token");
    }

    @Test
    void 코드_교환이_실패하면_아무것도_하지_않는다() {
        given(strategy.authenticate("bad")).willThrow(new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED));

        assertThatThrownBy(() -> service.withdraw(1L, OAuthProvider.NAVER, "bad"))
                .hasMessageContaining(ErrorCode.REAUTH_FAILED.getMessage());
        verifyNoInteractions(transactionTemplate, authProviderRepository);
    }

    @Test
    void 연결_해제가_실패해도_나머지_정리는_끝난다() {
        givenReauthAs("n-1");
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "n-1"))
                .willReturn(Optional.of(linked));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of(linked));
        doThrow(new RuntimeException("redis down")).when(withdrawnUserRegistry).mark(1L);
        doThrow(new RuntimeException("naver down")).when(strategy).revoke("provider-token");

        service.withdraw(1L, OAuthProvider.NAVER, "code");
        commit();

        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(strategy).revoke("provider-token");
    }

    @Test
    void 이미_탈퇴한_사용자면_다시_처리하지_않는다() {
        givenReauthAs("n-1");
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "n-1"))
                .willReturn(Optional.of(linked));
        user.withdraw();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service.withdraw(1L, OAuthProvider.NAVER, "code");

        verifyNoInteractions(itineraryRepository, itineraryStepRepository, refreshTokenRepository);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(authProviderRepository, never()).findByUserId(anyLong());
    }

    @Test
    void 분당_요청_한도를_넘으면_제공자를_호출하지_않고_거부한다() {
        given(valueOps.increment("withdraw:rate:1")).willReturn(6L);

        assertThatThrownBy(() -> service.withdraw(1L, OAuthProvider.NAVER, "code"))
                .hasMessageContaining(ErrorCode.WITHDRAWAL_RATE_LIMITED.getMessage());
        verifyNoInteractions(strategy);
    }

    @Test
    void 요청_제한_조회가_실패해도_탈퇴는_진행한다() {
        given(redisTemplate.opsForValue()).willThrow(new IllegalStateException("redis down"));
        givenReauthAs("n-1");
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "n-1"))
                .willReturn(Optional.of(linked));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of(linked));

        service.withdraw(1L, OAuthProvider.NAVER, "code");

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    private void givenReauthAs(String providerId) {
        given(strategy.authenticate("code")).willReturn(new OAuthSession(
                new OAuthUserInfo(providerId, "a@b.c", "닉", null), "provider-token"));
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }
}
