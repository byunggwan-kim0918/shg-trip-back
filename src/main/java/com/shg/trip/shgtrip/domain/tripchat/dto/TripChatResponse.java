package com.shg.trip.shgtrip.domain.tripchat.dto;

import java.util.List;

/**
 * 화면에 보일 문구는 모두 서버가 만든다. 프론트는 생성 요청 형식이나 반영 범위를 알지 않는다.
 *
 * @param status   ASKING(질문 카드)·REVIEW(확인 화면)·CONFIRMED(생성 시작됨)
 * @param back     "이전 답변"이 열 항목. CANCEL이면 수정 취소, null이면 버튼 없음
 * @param pending  답을 정리하는 중이면 그 답
 */
public record TripChatResponse(
        String id,
        long version,
        String status,
        Question question,
        Exchange lastExchange,
        List<Exchange> history,
        Summary summary,
        String back,
        String pending,
        String jobId,
        Integer days
) {
    public record Question(String id, String slot, String kind, String lead, String title, String description,
                           String placeholder, List<String> examples, boolean examplesOpen, String tool,
                           String prefill) {}

    public record Exchange(String question, String answer) {}

    public record Summary(List<Row> rows, List<Fix> fixes, boolean ready) {}

    /** @param filled 값이 있으면 true. 미정·미입력이면 value는 안내 문구다. */
    public record Row(String slot, String label, String value, List<String> notes, boolean filled) {}

    public record Fix(String questionId, String slot, String title, String description, String placeholder,
                      List<String> examples) {}
}
