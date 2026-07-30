package com.scenelog.common.error;

import org.springframework.http.HttpStatus;

/** 의도적으로 던지는 업무 예외. 상태코드를 직접 지정한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
