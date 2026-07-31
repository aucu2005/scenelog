package com.scenelog.reaction;

import com.scenelog.auth.User;
import com.scenelog.reaction.dto.ReactionBatchRequest;
import com.scenelog.reaction.dto.ReactionBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions/{sessionId}/reactions")
@RequiredArgsConstructor
@Tag(name = "반응 수집", description = "타임스탬프 반응 이벤트 배치 등록 (본인 세션만, 최대 500건, 멱등)")
public class ReactionController {

    private final ReactionService reactionService;

    @PostMapping
    @Operation(summary = "반응 배치 등록",
            description = "같은 clientEventId는 재전송해도 중복 저장되지 않는다(inserted=0). 남의 세션이면 403.")
    public ResponseEntity<ReactionBatchResponse> register(@AuthenticationPrincipal User user,
                                                          @PathVariable Long sessionId,
                                                          @Valid @RequestBody ReactionBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reactionService.registerBatch(user, sessionId, request));
    }
}
