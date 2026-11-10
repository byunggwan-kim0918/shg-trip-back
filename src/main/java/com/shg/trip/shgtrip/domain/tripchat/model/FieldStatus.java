package com.shg.trip.shgtrip.domain.tripchat.model;

/** 미정·상관없음·맡김은 생성 요청에서는 같아도 다시 묻지 않는 이유와 확인 화면 표시가 달라 구분한다. */
public enum FieldStatus {
    KNOWN,
    UNDECIDED,
    ANY,
    DELEGATED,
    NONE
}
