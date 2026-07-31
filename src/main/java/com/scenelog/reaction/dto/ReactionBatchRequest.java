package com.scenelog.reaction.dto;

import com.scenelog.reaction.ReactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 반응 배치 등록 요청. 상한 500은 무제한 페이로드 방어 (기획서 §6) */
public record ReactionBatchRequest(

        @NotEmpty(message = "이벤트 배열이 비어 있습니다")
        @Size(max = 500, message = "배치는 최대 500건입니다")
        List<@Valid Item> events
) {
    public record Item(
            @NotBlank(message = "clientEventId는 필수입니다")
            String clientEventId,

            @NotNull @Min(value = 0, message = "offsetSec은 0 이상이어야 합니다")
            Integer offsetSec,

            @NotNull(message = "reactionType은 필수입니다")
            ReactionType reactionType
    ) {}
}
