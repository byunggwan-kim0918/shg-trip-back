package com.shg.trip.shgtrip.global.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @InjectMocks private JwtAuthenticationFilter filter;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private WithdrawnUserRegistry withdrawnUserRegistry;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 탈퇴_표시가_있으면_유효한_토큰이어도_인증하지_않는다() throws Exception {
        givenValidToken(1L);
        given(withdrawnUserRegistry.isWithdrawn(1L)).willReturn(true);

        run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 탈퇴_표시가_없으면_인증한다() throws Exception {
        givenValidToken(1L);
        given(withdrawnUserRegistry.isWithdrawn(1L)).willReturn(false);

        run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private void givenValidToken(Long userId) {
        given(jwtTokenProvider.validateToken("tok")).willReturn(true);
        given(jwtTokenProvider.getAuthentication("tok")).willReturn(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(userId, "USER"), null, List.of()));
    }

    private void run() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tok");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
