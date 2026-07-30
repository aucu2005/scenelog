package com.scenelog.content;

import com.scenelog.common.error.ApiException;
import com.scenelog.content.dto.ContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;

    public Page<ContentResponse> list(Pageable pageable) {
        return contentRepository.findAll(pageable).map(ContentResponse::from);
    }

    public ContentResponse get(Long contentId) {
        return contentRepository.findById(contentId)
                .map(ContentResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다: " + contentId));
    }
}
