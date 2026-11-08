package com.shg.trip.shgtrip.global.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void 내부_원인이_담기는_코드는_기본_문구만_사용자에게_보낸다() {
        BusinessException e = new BusinessException(ErrorCode.AI_SERVICE_ERROR, "생성 응답에서 장소 선택 데이터를 추출할 수 없습니다.");
        assertThat(e.userMessage()).isEqualTo(ErrorCode.AI_SERVICE_ERROR.getMessage());
        assertThat(e.getMessage()).contains("장소 선택 데이터");
    }

    @Test
    void 그_외_코드는_상세_문구를_그대로_보낸다() {
        BusinessException e = new BusinessException(ErrorCode.GENERATION_QUOTA_EXCEEDED, "약 3일 후 다시 생성할 수 있어요.");
        assertThat(e.userMessage()).isEqualTo("약 3일 후 다시 생성할 수 있어요.");
    }

    @Test
    void 상세_문구가_없으면_기본_문구를_보낸다() {
        assertThat(new BusinessException(ErrorCode.INSUFFICIENT_PLACE_DATA).userMessage())
                .isEqualTo(ErrorCode.INSUFFICIENT_PLACE_DATA.getMessage());
    }
}
