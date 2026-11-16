package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryDayWordsTest {

    @Test
    @DisplayName("그날의 시간을 여는 자리의 틀린 일차 표현을 실제 일차로 고친다")
    void fixesWrongDayWords() {
        assertThat(StoryDayWords.fix("둘째 날 아침, 바다를 보며 시작해요.", 3, 5)).isEqualTo("셋째 날 아침, 바다를 보며 시작해요.");
        assertThat(StoryDayWords.fix("둘째날 저녁은 회로 마무리해요.", 3, 5)).isEqualTo("셋째 날 저녁은 회로 마무리해요.");
        assertThat(StoryDayWords.fix("마지막 날 오후의 여유를 즐겨요.", 4, 5)).isEqualTo("넷째 날 오후의 여유를 즐겨요.");
        assertThat(StoryDayWords.fix("이틀째 아침, 시장으로 가요.", 3, 5)).isEqualTo("사흘째 아침, 시장으로 가요.");
        assertThat(StoryDayWords.fix("3일차 일정은 회로 마무리해요.", 2, 3)).isEqualTo("2일차 일정은 회로 마무리해요.");
        assertThat(StoryDayWords.fix("둘째 날 오후 한적한 해변을 걸어요.", 3, 5)).isEqualTo("셋째 날 오후 한적한 해변을 걸어요.");
        assertThat(StoryDayWords.fix("둘째 날 아침 맑은 하늘 아래 출발해요.", 3, 5)).isEqualTo("셋째 날 아침 맑은 하늘 아래 출발해요.");
    }

    @Test
    @DisplayName("맞는 표현, 다른 날을 돌아보거나 미리 말하는 문장, 마지막 밤·저녁은 그대로 둔다")
    void keepsCorrectAndReferringWords() {
        assertThat(StoryDayWords.fix("셋째 날 아침이 밝아요.", 3, 5)).isEqualTo("셋째 날 아침이 밝아요.");
        assertThat(StoryDayWords.fix("둘째 날 들렀던 시장을 지나요.", 3, 5)).isEqualTo("둘째 날 들렀던 시장을 지나요.");
        assertThat(StoryDayWords.fix("첫날 본 바다와는 또 다른 풍경이에요.", 3, 5)).isEqualTo("첫날 본 바다와는 또 다른 풍경이에요.");
        assertThat(StoryDayWords.fix("둘째 날 들른 시장 근처예요.", 3, 5)).isEqualTo("둘째 날 들른 시장 근처예요.");
        assertThat(StoryDayWords.fix("둘째 날은 비가 와서 걸었던 길이에요.", 3, 5)).isEqualTo("둘째 날은 비가 와서 걸었던 길이에요.");
        assertThat(StoryDayWords.fix("여행 마지막 날을 앞두고 여유롭게 쉬어요.", 4, 5))
                .isEqualTo("여행 마지막 날을 앞두고 여유롭게 쉬어요.");
        assertThat(StoryDayWords.fix("내일 셋째 날 아침에는 일출을 봐요.", 2, 4)).isEqualTo("내일 셋째 날 아침에는 일출을 봐요.");
        assertThat(StoryDayWords.fix("1일차에 이어 오늘도 바다를 따라가요.", 2, 4)).isEqualTo("1일차에 이어 오늘도 바다를 따라가요.");
        assertThat(StoryDayWords.fix("오늘의 마지막 저녁 코스예요.", 2, 4)).isEqualTo("오늘의 마지막 저녁 코스예요.");
        assertThat(StoryDayWords.fix("숙소로 돌아와 여행의 마지막 밤을 보내요.", 4, 5))
                .isEqualTo("숙소로 돌아와 여행의 마지막 밤을 보내요.");
        assertThat(StoryDayWords.fix("내일은 셋째 날 아침부터 바다로 가요.", 2, 4)).isEqualTo("내일은 셋째 날 아침부터 바다로 가요.");
        assertThat(StoryDayWords.fix("다음 날인 셋째 날 아침에는 일출을 봐요.", 2, 4))
                .isEqualTo("다음 날인 셋째 날 아침에는 일출을 봐요.");
        assertThat(StoryDayWords.fix("첫날 저녁에 먹은 회가 생각나요.", 3, 5)).isEqualTo("첫날 저녁에 먹은 회가 생각나요.");
        assertThat(StoryDayWords.fix("둘째 날 저녁 먹은 곳 근처예요.", 3, 5)).isEqualTo("둘째 날 저녁 먹은 곳 근처예요.");
        assertThat(StoryDayWords.fix("둘째 날 저녁 맛있게 먹은 회가 생각나요.", 3, 5))
                .isEqualTo("둘째 날 저녁 맛있게 먹은 회가 생각나요.");
        assertThat(StoryDayWords.fix("마지막 날 아침에는 공항으로 가니 오늘은 여유롭게 걸어요.", 3, 4))
                .isEqualTo("마지막 날 아침에는 공항으로 가니 오늘은 여유롭게 걸어요.");
        assertThat(StoryDayWords.fix("첫날밤은 바다 소리로 기억돼요.", 2, 3)).isEqualTo("첫날밤은 바다 소리로 기억돼요.");
        assertThat(StoryDayWords.fix("마지막 날 아침, 공항으로 가요.", 5, 5)).isEqualTo("마지막 날 아침, 공항으로 가요.");
        assertThat(StoryDayWords.fix("마지막 날 아침, 공항으로 가요.", 2, 0)).isEqualTo("마지막 날 아침, 공항으로 가요.");
        assertThat(StoryDayWords.fix("99999999999일차 아침, 출발해요.", 2, 3)).isEqualTo("99999999999일차 아침, 출발해요.");
        assertThat(StoryDayWords.fix(null, 2, 3)).isNull();
    }
}
