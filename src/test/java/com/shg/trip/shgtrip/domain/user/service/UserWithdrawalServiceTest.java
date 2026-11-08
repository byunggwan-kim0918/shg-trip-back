package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.domain.auth.entity.UserAuthProvider;
import com.shg.trip.shgtrip.domain.auth.repository.RefreshTokenRepository;
import com.shg.trip.shgtrip.domain.auth.repository.UserAuthProviderRepository;
import com.shg.trip.shgtrip.domain.auth.service.WithdrawnIdentityHasher;
import com.shg.trip.shgtrip.domain.auth.service.oauth.KakaoUnlinkClient;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @InjectMocks private UserWithdrawalService service;

    @Mock private UserRepository userRepository;
    @Mock private UserAuthProviderRepository authProviderRepository;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private GenerationTraceRepository generationTraceRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private WithdrawnUserRegistry withdrawnUserRegistry;
    @Mock private WithdrawnIdentityHasher identityHasher;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private TravelPlannerService travelPlannerService;
    @Mock private KakaoUnlinkClient kakaoUnlinkClient;

    private User user;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        user = User.builder().email("a@b.c").nickname("닉네임").profileImage("http://img").build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void 확인_문구가_다르면_아무것도_하지_않는다() {
        assertThatThrownBy(() -> service.withdraw(1L, "탈퇴할래요"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.WITHDRAWAL_CONFIRM_MISMATCH.getMessage());
        verifyNoInteractions(userRepository, itineraryRepository, authProviderRepository);
    }

    @Test
    void 개인정보를_지우고_커밋_후_세션과_카카오_연결을_끊는다() {
        UserAuthProvider kakao = UserAuthProvider.builder()
                .userId(1L).provider(OAuthProvider.KAKAO).providerId("k-1").build();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of(kakao));
        given(identityHasher.hash(OAuthProvider.KAKAO, "k-1")).willReturn(Optional.of("h"));

        service.withdraw(1L, "탈퇴");

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getEmail()).isEqualTo("withdrawn-1@invalid");
        assertThat(user.getNickname()).isNull();
        assertThat(user.getProfileImage()).isNull();
        assertThat(user.getDeletedAt()).isNotNull();
        verify(generationTraceRepository).deleteByUserId(1L);
        verify(itineraryRepository).softDeleteAllByUserId(eq(1L), any());
        verify(authProviderRepository).deleteAll(List.of(kakao));
        verify(travelPlannerService).cancelActiveJob(1L);
        verify(refreshTokenRepository, never()).deleteByUserId(anyLong());

        commit();

        verify(withdrawnUserRegistry).mark(1L);
        verify(refreshTokenRepository).deleteByUserId(1L);
        verify(generationPolicyService).parkCountersForWithdrawal(1L, List.of("h"));
        verify(kakaoUnlinkClient).unlink("k-1");
    }

    @Test
    void 커밋_후_정리가_하나_실패해도_나머지는_계속한다() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserId(1L)).willReturn(List.of());
        doThrow(new RuntimeException("redis down")).when(withdrawnUserRegistry).mark(1L);

        service.withdraw(1L, "탈퇴");
        commit();

        verify(refreshTokenRepository).deleteByUserId(1L);
    }

    @Test
    void 이미_탈퇴한_사용자면_다시_처리하지_않는다() {
        user.withdraw();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service.withdraw(1L, "탈퇴");

        verifyNoInteractions(itineraryRepository, authProviderRepository, kakaoUnlinkClient);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }
}
