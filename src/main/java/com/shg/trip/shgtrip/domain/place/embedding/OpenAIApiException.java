package com.shg.trip.shgtrip.domain.place.embedding;

/**
 * OpenAI API 호출 실패 시 발생하는 예외.
 */
public class OpenAIApiException extends RuntimeException {

    public OpenAIApiException(String message) {
        super(message);
    }

    public OpenAIApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
