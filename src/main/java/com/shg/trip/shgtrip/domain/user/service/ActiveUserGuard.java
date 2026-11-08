package com.shg.trip.shgtrip.domain.user.service;

import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 표시(Redis)를 못 남겼거나 탈퇴와 동시에 진행되던 작업이 탈퇴 계정에 데이터를 쓰지 못하게 막는다.
 */
@Component
@RequiredArgsConstructor
public class ActiveUserGuard {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void requireActive(Long userId) {
        check(userRepository.findById(userId).orElse(null));
    }

    /**
     * 호출한 트랜잭션이 끝날 때까지 사용자 행을 공유 잠금한다.
     * 탈퇴는 같은 행을 배타 잠금하므로, 탈퇴 전에 커밋된 쓰기는 탈퇴가 정리하고 이후 쓰기는 여기서 거부된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActive(Long userId) {
        check(userRepository.findByIdForShare(userId).orElse(null));
    }

    private static void check(User user) {
        if (user == null || user.isWithdrawn()) {
            throw new BusinessException(ErrorCode.USER_WITHDRAWN);
        }
    }
}
