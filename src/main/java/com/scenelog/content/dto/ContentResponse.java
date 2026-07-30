package com.scenelog.content.dto;

import com.scenelog.content.Content;
import com.scenelog.content.ContentType;

import java.time.LocalDate;

public record ContentResponse(
        Long contentId,
        Integer tmdbId,
        String title,
        ContentType contentType,
        Integer durationSec,
        LocalDate releaseDate
) {
    public static ContentResponse from(Content c) {
        return new ContentResponse(c.getContentId(), c.getTmdbId(), c.getTitle(),
                c.getContentType(), c.getDurationSec(), c.getReleaseDate());
    }
}
