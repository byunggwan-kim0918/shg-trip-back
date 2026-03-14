package com.shg.trip.shgtrip.domain.user.repository;

import com.shg.trip.shgtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByNickname(String nickname);
}
