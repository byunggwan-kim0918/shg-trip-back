package com.shg.trip.shgtrip.domain.place.embedding;

public class OpenAIApiException extends RuntimeException {

    public OpenAIApiException(String message) {
        super(message);
    }

    public OpenAIApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
