package com.scenelog.content;

import com.scenelog.content.dto.ContentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents")
@RequiredArgsConstructor
@Tag(name = "콘텐츠", description = "ETL이 적재한 콘텐츠 조회")
public class ContentController {

    private final ContentService contentService;

    @GetMapping
    @Operation(summary = "콘텐츠 목록", description = "페이징 조회. 예: ?page=0&size=20&sort=title,asc")
    public Page<ContentResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return contentService.list(pageable);
    }

    @GetMapping("/{contentId}")
    @Operation(summary = "콘텐츠 상세")
    public ContentResponse get(@PathVariable Long contentId) {
        return contentService.get(contentId);
    }
}
