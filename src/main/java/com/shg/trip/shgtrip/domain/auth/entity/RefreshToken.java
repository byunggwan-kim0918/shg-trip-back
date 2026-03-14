package com.shg.trip.shgtrip.domain.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

import java.util.concurrent.TimeUnit;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("refresh_token")
public class RefreshToken {

    @Id
    private String token;

    @Indexed
    private Long userId;

    private boolean revoked;

    @TimeToLive(unit = TimeUnit.MILLISECONDS)
    private long expiration;

    public void revoke() {
        this.revoked = true;
    }
}
