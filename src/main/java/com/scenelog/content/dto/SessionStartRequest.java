package com.scenelog.content.dto;

import jakarta.validation.constraints.NotNull;

public record SessionStartRequest(

        @NotNull(message = "contentId는 필수입니다")
        Long contentId
) {}
