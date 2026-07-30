package com.scenelog.common.error;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 모든 실패 응답의 단일 형식.
 * 형식을 하나로 고정해야 클라이언트가 분기하기 쉽고, 로그에서도 검색이 쉽다.
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String message,
        List<String> details
) {
    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(OffsetDateTime.now(), status, message, List.of());
    }

    public static ErrorResponse of(int status, String message, List<String> details) {
        return new ErrorResponse(OffsetDateTime.now(), status, message, details);
    }
}
