package com.shg.trip.shgtrip.domain.tripchat.model;

public enum QuestionKind {
    BASE,
    FOLLOW_UP,
    /** 이미 답한 항목을 다시 답하게 연 질문 */
    EDIT,
    /** 확인 화면에서 생성에 꼭 필요한 정보만 보완하는 질문 */
    FIX,
    /** 확인 화면의 "더 알려주고 싶은 게 있어요" */
    EXTRA
}
