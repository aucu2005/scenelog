package com.scenelog.reaction.dto;

/** inserted + skippedDuplicate = 요청 건수. 멱등 재전송이면 inserted=0이 정상이다 */
public record ReactionBatchResponse(int requested, int inserted, int skippedDuplicate) {}
