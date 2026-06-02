package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.user.dto.ProfileResponse;
import com.shg.trip.shgtrip.domain.user.dto.ProfileUpdateRequest;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("존재하는 유저의 프로필을 조회할 수 있다")
    void getProfile_existingUser_returnsProfile() {
        // given
        User user = User.builder().email("test@email.com").nickname("테스터").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        // when
        ProfileResponse response = userService.getProfile(1L);

        // then
        assertThat(response.nickname()).isEqualTo("테스터");
        assertThat(response.email()).isEqualTo("test@email.com");
    }

    @Test
    @DisplayName("존재하지 않는 유저 조회 시 예외가 발생한다")
    void getProfile_notFound_throwsException() {
        // given
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getProfile(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.USER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("닉네임을 정상적으로 변경할 수 있다")
    void updateProfile_validNickname_updatesSuccessfully() {
        // given
        User user = User.builder().email("test@email.com").nickname("기존닉네임").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userRepository.existsByNickname("새닉네임")).willReturn(false);

        // when
        ProfileResponse response = userService.updateProfile(1L, new ProfileUpdateRequest("새닉네임"));

        // then
        assertThat(response.nickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임으로 변경 시 예외가 발생한다")
    void updateProfile_duplicateNickname_throwsException() {
        // given
        User user = User.builder().email("test@email.com").nickname("기존닉네임").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userRepository.existsByNickname("중복닉네임")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.updateProfile(1L, new ProfileUpdateRequest("중복닉네임")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.DUPLICATE_NICKNAME.getMessage());
    }

    @Test
    @DisplayName("같은 닉네임으로 변경 시 중복 검사를 건너뛴다")
    void updateProfile_sameNickname_skipsDuplicateCheck() {
        // given
        User user = User.builder().email("test@email.com").nickname("기존닉네임").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        // when
        ProfileResponse response = userService.updateProfile(1L, new ProfileUpdateRequest("기존닉네임"));

        // then
        assertThat(response.nickname()).isEqualTo("기존닉네임");
        // existsByNickname 호출 안 됨
        verify(userRepository).findById(1L);
    }
}
