package com.shg.trip.shgtrip.domain.user.entity;

import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(length = 50)
    private String nickname;

    @Column(name = "profile_image", length = 500)
    private String profileImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    public User(String email, String nickname, String profileImage) {
        this.email = email;
        this.nickname = nickname;
        this.profileImage = profileImage;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 개인정보를 지우고 행만 남긴다. email은 NOT NULL이라 식별 불가능한 값으로 바꾼다. */
    public void withdraw() {
        this.email = "withdrawn-" + id + "@invalid";
        this.nickname = null;
        this.profileImage = null;
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isWithdrawn() {
        return status == UserStatus.WITHDRAWN;
    }

    public boolean isNewUser() {
        return this.nickname == null || this.nickname.isBlank();
    }
}
