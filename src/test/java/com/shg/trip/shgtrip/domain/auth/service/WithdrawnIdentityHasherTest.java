package com.shg.trip.shgtrip.domain.auth.service;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import com.shg.trip.shgtrip.global.config.WithdrawalProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawnIdentityHasherTest {

    @Test
    void 키가_없으면_해시를_만들지_않는다() {
        WithdrawnIdentityHasher hasher = new WithdrawnIdentityHasher(new WithdrawalProperties(null, "", null));
        assertThat(hasher.hash(OAuthProvider.KAKAO, "123")).isEmpty();
    }

    @Test
    void 같은_계정은_같은_해시_다른_제공자는_다른_해시() {
        WithdrawnIdentityHasher hasher = new WithdrawnIdentityHasher(new WithdrawalProperties(null, "secret", null));
        String kakao = hasher.hash(OAuthProvider.KAKAO, "123").orElseThrow();
        assertThat(hasher.hash(OAuthProvider.KAKAO, "123")).contains(kakao);
        assertThat(hasher.hash(OAuthProvider.NAVER, "123")).isPresent().get().isNotEqualTo(kakao);
        assertThat(kakao).doesNotContain("123").hasSize(64);
    }
}
