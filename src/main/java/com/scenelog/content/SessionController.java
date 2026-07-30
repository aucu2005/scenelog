package com.scenelog.content;

import com.scenelog.auth.User;
import com.scenelog.content.dto.SessionResponse;
import com.scenelog.content.dto.SessionStartRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 시청 세션. 모든 엔드포인트가 인증을 요구한다.
 *
 * <p>{@code @AuthenticationPrincipal User}가 동작하려면 JwtAuthenticationFilter가
 * principal에 User 엔티티를 넣어야 한다 (해당 클래스의 "계약" 주석 참고).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "시청 세션", description = "세션 시작·종료·내 이력")
public class SessionController {

    private final SessionService sessionService;

    @PostMapping("/sessions")
    @Operation(summary = "세션 시작")
    public ResponseEntity<SessionResponse> start(@AuthenticationPrincipal User user,
                                                 @Valid @RequestBody SessionStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.start(user, request.contentId()));
    }

    @PatchMapping("/sessions/{sessionId}/end")
    @Operation(summary = "세션 종료", description = "본인 세션이 아니면 403")
    public SessionResponse end(@AuthenticationPrincipal User user, @PathVariable Long sessionId) {
        return sessionService.end(user, sessionId);
    }

    @GetMapping("/me/history")
    @Operation(summary = "내 시청 이력")
    public List<SessionResponse> myHistory(@AuthenticationPrincipal User user) {
        return sessionService.myHistory(user);
    }
}
