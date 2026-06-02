package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.dto.OAuthCallbackRequest;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthLoginResult;
import com.shg.trip.shgtrip.domain.auth.dto.OAuthUserInfo;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock private OAuthStrategyFactory oAuthStrategyFactory;
    @Mock private UserRepository userRepository;
    @Mock private UserAuthProviderRepository authProviderRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private OAuthProviderStrategy oAuthProviderStrategy;

    @Test
    @DisplayName("신규 유저 OAuth 로그인 시 유저가 생성되고 토큰이 발급된다")
    void processOAuthCallback_newUser_createsUserAndIssuesTokens() {
        // given
        OAuthCallbackRequest request = new OAuthCallbackRequest("kakao", "auth-code");
        OAuthUserInfo userInfo = new OAuthUserInfo("kakao-123", "test@email.com", "테스터", null);
        User newUser = User.builder().email("test@email.com").nickname("테스터").build();

        given(oAuthStrategyFactory.getStrategy(OAuthProvider.KAKAO)).willReturn(oAuthProviderStrategy);
        given(oAuthProviderStrategy.getUserInfo("auth-code")).willReturn(userInfo);
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-123"))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(newUser);
        given(authProviderRepository.save(any(UserAuthProvider.class))).willReturn(mock(UserAuthProvider.class));
        given(jwtTokenProvider.createAccessToken(any(), any())).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshExpiration()).willReturn(604800000L);
        given(refreshTokenRepository.save(any(RefreshToken.class))).willReturn(mock(RefreshToken.class));

        // when
        OAuthLoginResult result = authService.processOAuthCallback(request);

        // then
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        verify(userRepository).save(any(User.class));
        verify(authProviderRepository).save(any(UserAuthProvider.class));
    }

    @Test
    @DisplayName("기존 유저 OAuth 로그인 시 유저 생성 없이 토큰만 발급된다")
    void processOAuthCallback_existingUser_issuesTokensWithoutCreatingUser() {
        // given
        OAuthCallbackRequest request = new OAuthCallbackRequest("kakao", "auth-code");
        OAuthUserInfo userInfo = new OAuthUserInfo("kakao-123", "test@email.com", "테스터", null);
        User existingUser = User.builder().email("test@email.com").nickname("테스터").build();
        UserAuthProvider existingAuth = UserAuthProvider.builder()
                .userId(1L).provider(OAuthProvider.KAKAO).providerId("kakao-123").build();

        given(oAuthStrategyFactory.getStrategy(OAuthProvider.KAKAO)).willReturn(oAuthProviderStrategy);
        given(oAuthProviderStrategy.getUserInfo("auth-code")).willReturn(userInfo);
        given(authProviderRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-123"))
                .willReturn(Optional.of(existingAuth));
        given(userRepository.findById(1L)).willReturn(Optional.of(existingUser));
        given(jwtTokenProvider.createAccessToken(any(), any())).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken()).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshExpiration()).willReturn(604800000L);
        given(refreshTokenRepository.save(any(RefreshToken.class))).willReturn(mock(RefreshToken.class));

        // when
        authService.processOAuthCallback(request);

        // then
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("지원하지 않는 OAuth provider면 예외가 발생한다")
    void processOAuthCallback_invalidProvider_throwsException() {
        // given
        OAuthCallbackRequest request = new OAuthCallbackRequest("invalid", "auth-code");

        // when & then
        assertThatThrownBy(() -> authService.processOAuthCallback(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.INVALID_PROVIDER.getMessage());
    }

    @Test
    @DisplayName("revoked된 refresh token으로 갱신 시 전체 세션이 무효화된다")
    void refreshAccessToken_revokedToken_invalidatesAllSessions() {
        // given
        RefreshToken revokedToken = RefreshToken.builder()
                .token("revoked-token").userId(1L).revoked(true).expiration(604800000L).build();
        given(refreshTokenRepository.findByToken("revoked-token")).willReturn(Optional.of(revokedToken));

        // when & then
        assertThatThrownBy(() -> authService.refreshAccessToken("revoked-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED.getMessage());

        verify(refreshTokenRepository).deleteByUserId(1L);
    }

    @Test
    @DisplayName("존재하지 않는 refresh token으로 갱신 시 예외가 발생한다")
    void refreshAccessToken_notFound_throwsException() {
        // given
        given(refreshTokenRepository.findByToken("unknown-token")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.refreshAccessToken("unknown-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.REFRESH_TOKEN_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("로그아웃 시 refresh token이 삭제된다")
    void logout_deletesRefreshToken() {
        // given
        RefreshToken token = RefreshToken.builder()
                .token("valid-token").userId(1L).revoked(false).expiration(604800000L).build();
        given(refreshTokenRepository.findByToken("valid-token")).willReturn(Optional.of(token));

        // when
        authService.logout("valid-token");

        // then
        verify(refreshTokenRepository).delete(token);
    }
}
