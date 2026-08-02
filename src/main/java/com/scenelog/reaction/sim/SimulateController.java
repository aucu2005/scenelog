package com.scenelog.reaction.sim;

import com.scenelog.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 시연 모드 트리거 (ROLE_ADMIN — /api/admin/** 경로로 SecurityConfig가 인가를 강제한다).
 * 프론트엔드가 없으므로 이 API가 "시청자들이 반응 버튼을 눌렀다"를 재현한다 (기획서 §5-B).
 */
@RestController
@RequestMapping("/api/admin/simulate")
@RequiredArgsConstructor
@Tag(name = "관리자 · 시뮬레이터", description = "각본 기반 반응 이벤트 생성 — 시연·검증용 (ROLE_ADMIN)")
public class SimulateController {

    private final SimulateService simulateService;

    @PostMapping
    @Operation(summary = "반응 시뮬레이션 실행",
            description = "각본(정답 피크 포함)대로 이벤트를 생성해 실제 수집 경로로 등록한다. "
                    + "같은 파라미터로 재실행하면 inserted=0 (멱등). 응답의 answerPeaks가 day4 검출의 정답지다.")
    public Map<String, Object> simulate(@AuthenticationPrincipal User caller,
                                        @RequestParam long contentId,
                                        @RequestParam(defaultValue = "20") int users,
                                        @RequestParam(defaultValue = "42") long seed) {
        // 공개 데모 서버 보호: 이벤트를 전량 메모리에 생성하므로 상한 없이는 힙(384m) 고갈 벡터가 된다
        if (users < 1 || users > 200) {
            throw new com.scenelog.common.error.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "users는 1~200 범위여야 합니다");
        }
        return simulateService.simulate(caller, contentId, users, seed);
    }
}
