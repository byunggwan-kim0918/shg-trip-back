package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ActiveUserGuardTest {

    @InjectMocks private ActiveUserGuard guard;
    @Mock private UserRepository userRepository;

    @Test
    void 탈퇴한_사용자면_쓰기를_거부한다() {
        User user = User.builder().email("a@b.c").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.withdraw();
        given(userRepository.findByIdForShare(1L)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> guard.lockActive(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorCode.USER_WITHDRAWN.getMessage());
    }

    @Test
    void 활성_사용자는_통과한다() {
        given(userRepository.findById(1L)).willReturn(Optional.of(User.builder().email("a@b.c").build()));
        assertThatCode(() -> guard.requireActive(1L)).doesNotThrowAnyException();
    }

    @Test
    void 사용자가_없으면_거부한다() {
        given(userRepository.findById(1L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireActive(1L)).isInstanceOf(BusinessException.class);
    }
}
