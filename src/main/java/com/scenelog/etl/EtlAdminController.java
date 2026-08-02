package com.scenelog.etl;

import com.scenelog.etl.dto.EtlRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ETL 관리자 API. 경로가 /api/admin/** 이므로 SecurityConfig에 의해 ROLE_ADMIN만 접근 가능하다.
 * 무인증이면 외부에서 ETL을 무한 트리거해 rate limit·DB를 소모시킬 수 있다 (기획서 §6).
 */
@RestController
@RequestMapping("/api/admin/etl")
@RequiredArgsConstructor
@Tag(name = "관리자 · ETL", description = "수집 트리거 · 품질 리포트 · 격리 재처리 (ROLE_ADMIN)")
public class EtlAdminController {

    private final EtlService etlService;
    private final RejectedRecordRepository rejectedRecordRepository;

    @PostMapping("/run")
    @Operation(summary = "ETL 실행", description = "TMDB 수집→검증→정제→적재. pages×20건 수집. 결과로 품질 리포트를 반환한다.")
    public EtlRunResponse run(@RequestParam(defaultValue = "5") int pages) {
        // 공개 데모 서버 보호: 상한 없이는 TMDB 쿼터 소진 + 장시간 트랜잭션 점유 벡터가 된다
        if (pages < 1 || pages > 20) {
            throw new com.scenelog.common.error.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "pages는 1~20 범위여야 합니다");
        }
        return EtlRunResponse.from(etlService.run(pages));
    }

    @GetMapping("/quality-reports")
    @Operation(summary = "품질 리포트 목록", description = "실행마다 1행. 정합성·누락·중복 카운트 (최신순)")
    public List<EtlRunResponse> reports() {
        return etlService.reports().stream().map(EtlRunResponse::from).toList();
    }

    @PostMapping("/reprocess")
    @Operation(summary = "격리 레코드 재처리", description = "rejected_records 중 미처리분을 저장된 원본으로 재검증·적재")
    public Map<String, Object> reprocess() {
        int recovered = etlService.reprocessRejected();
        long remaining = rejectedRecordRepository.countByReprocessedAtIsNull();
        return Map.of("recovered", recovered, "remainingRejected", remaining);
    }
}
