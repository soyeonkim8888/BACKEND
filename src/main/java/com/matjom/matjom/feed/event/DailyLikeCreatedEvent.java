package com.matjom.matjom.feed.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
// 9월26일 재수정: 좋아요 생성 후 통계/알림 파이프라인으로 전달할 이벤트 페이로드
public record DailyLikeCreatedEvent(
        UUID likeId,
        UUID userId,
        Long placeId,
        Long visitId,
        LocalDate dateKst,
        OffsetDateTime createdAt
) {}