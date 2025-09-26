package com.matjom.matjom.feed.service;

import com.matjom.matjom.feed.dto.request.DailyLikeCreateRequestDTO;
import com.matjom.matjom.feed.dto.response.DailyLikeResponseDTO;
import com.matjom.matjom.feed.dto.response.EligibilityCheckResponseDTO;
import com.matjom.matjom.feed.entity.likes.DailyLike;
import com.matjom.matjom.feed.entity.likes.LikeStatus;
import com.matjom.matjom.feed.repository.DailyLikeRepository;
import com.matjom.matjom.common.exception.base.FeedException;
import com.matjom.matjom.common.exception.message.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Slf4j
@Transactional(readOnly = true)
public class DailyLikeService {
    private final DailyLikeRepository dailyLikeRepository;
    private final Clock clock;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");


    @Autowired                                                          // 수정제안 2024-09-24: 프로덕션 기본 시계
    public DailyLikeService(DailyLikeRepository dailyLikeRepository, ApplicationEventPublisher eventPublisher ) {
        this(dailyLikeRepository, Clock.system(KST),eventPublisher);
    }

    DailyLikeService(DailyLikeRepository dailyLikeRepository, Clock clock,ApplicationEventPublisher eventPublisher) { // 수정제안 2024-09-24: 테스트 주입용
        this.dailyLikeRepository = dailyLikeRepository;
        this.clock = clock.withZone(KST);
    }
    // TODO: VisitService 주입 필요 (visits 테이블 조회용)


    /**
     * 좋아요 작성 자격 확인
     * UC-Feed-02: 기회 확인 로직 통합
     */
    public EligibilityCheckResponseDTO checkLikeEligibility(UUID userId, Long placeId, Long visitId) {
        log.info("좋아요 작성 자격 확인: userId={}, placeId={}, visitId={}", userId, placeId, visitId);

        // 1. visits 테이블 조회: arrived_at NOT NULL AND state = 'ARRIVED' 확인
        // TODO: VisitService.findByIdAndUserId(visitId, userId) 호출
        // Visit visit = visitService.findByIdAndUserId(visitId, userId);
        // if (visit == null) {
        //     return EligibilityCheckResponse.notEligible("방문 기록을 찾을 수 없습니다", visitId, false, false, false);
        // }
        // if (visit.getArrivedAt() == null || !visit.getState().equals("ARRIVED")) {
        //     return EligibilityCheckResponse.notEligible("도착 확인 후 이용 가능", visitId, false, false, true);
        // }

        // 2. 당일 내 방문인지 확인 (date_kst = today)
        // LocalDate visitDate = visit.getArrivedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        // if (!visitDate.equals(LocalDate.now(ZoneId.of("Asia/Seoul")))) {
        //     return EligibilityCheckResponse.notEligible("당일 방문에만 이용 가능", visitId, true, false, false);
        // }

        // 3. 이미 좋아요 작성했는지 확인
        boolean alreadyWritten = dailyLikeRepository.existsByVisitId(visitId);
        if (alreadyWritten) {
            return EligibilityCheckResponseDTO.notEligible("이미 좋아요를 누르셨습니다", visitId, true, true, true);
        }

        return EligibilityCheckResponseDTO.eligible(visitId);
    }

    /**
     * 좋아요 등록
     * UC-Feed-02: 기회 확인 통합 방식
     */
    @Transactional
    public DailyLikeResponseDTO createLike(UUID userId, DailyLikeCreateRequestDTO request) {
        log.info("좋아요 생성 요청: userId={}, request={},visitId={}",
        maskUserId(userId), request.getPlaceId(), request.getVisitId());

        // 1. 기회 확인 로직
        EligibilityCheckResponseDTO eligibility = checkLikeEligibility(
                userId, request.getPlaceId(), request.getVisitId()
        );

        if (!eligibility.getEligible()) {
            // 9월24일 재수정: IllegalStateException -> FeedException 변경
            // 구체적인 실패 사유에 따른 예외 처리
            if (!eligibility.getVisitArrived()) {
                throw new FeedException(ErrorCode.ARRIVAL_NOT_CONFIRMED, eligibility.getReason());
            }
            if (eligibility.getAlreadyWritten()) {
                throw new FeedException(ErrorCode.LIKE_ALREADY_EXISTS, eligibility.getReason());
            }
            throw new FeedException(ErrorCode.LIKE_NOT_ALLOWED, eligibility.getReason());
        }

        // 2. 좋아요 생성 및 저장
        LocalDate today = LocalDate.now(clock.withZone(KST));
        //LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        DailyLike dailyLike = DailyLike.builder()
                .userId(userId)
                .placeId(request.getPlaceId())
                .visitId(request.getVisitId())
                .dateKst(today)
                .status(LikeStatus.ACTIVE)
                .build();

        DailyLike savedLike = dailyLikeRepository.save(dailyLike);

        // 3. 통계 업데이트 (비동기 또는 이벤트)
        // TODO: 통계 업데이트 이벤트 발생
        eventPublisher.publishEvent(new DailyLikeCreatedEvent( // 9월26일 재수정: 좋아요 생성 이벤트 발행
                savedLike.getId(),
                savedLike.getUserId(),
                savedLike.getPlaceId(),
                savedLike.getVisitId(),
                savedLike.getDateKst(),
                savedLike.getCreatedAt()
        ));
        log.info("좋아요 등록 완료: likeId={}", savedLike.getId());
        return DailyLikeResponseDTO.from(savedLike);
    }

    /**
     * 좋아요 취소
     */
    @Transactional
    public void cancelLike(UUID userId, UUID likeId) {
        DailyLike dailyLike = dailyLikeRepository.findById(likeId)
                // 9월24일 재수정: IllegalArgumentException -> FeedException 변경
                .orElseThrow(() -> new FeedException(
                        ErrorCode.LIKE_NOT_FOUND,
                        "좋아요 내역을 찾을 수 없습니다."));

        // 작성자 확인
        if (!dailyLike.getUserId().equals(userId)) {
            // 9월24일 재수정: IllegalArgumentException -> FeedException 변경
            throw new FeedException(ErrorCode.FORBIDDEN, "자신의 좋아요만 취소할 수 있습니다");
        }

        // 이미 취소된 상태인지 확인
        if (dailyLike.getStatus() == LikeStatus.CANCELLED) {
            // 9월24일 재수정: IllegalStateException -> FeedException 변경
            throw new FeedException(ErrorCode.LIKE_NOT_ALLOWED, "이미 취소된 좋아요입니다");
        }

        dailyLike.cancel();

        log.info("좋아요 취소 완료: likeId={}", likeId);
    }

    /**
     * 좋아요 재등록
     * UC-Feed-02: PUT 방식
     */
    @Transactional
    public DailyLikeResponseDTO reactivateLike(UUID userId, UUID likeId) {
        DailyLike dailyLike = dailyLikeRepository.findById(likeId)
                // 9월24일 재수정: IllegalArgumentException -> FeedException 변경
                .orElseThrow(() -> new FeedException(ErrorCode.USER_NOT_FOUND, "좋아요를 찾을 수 없습니다"));

        // 작성자 확인
        if (!dailyLike.getUserId().equals(userId)) {
            // 9월24일 재수정: IllegalArgumentException -> FeedException 변경
            throw new FeedException(ErrorCode.FORBIDDEN, "자신의 좋아요만 재등록할 수 있습니다");
        }

        // 이미 활성 상태인지 확인
        if (dailyLike.getStatus() == LikeStatus.ACTIVE) {
            // 9월24일 재수정: IllegalStateException -> FeedException 변경
            throw new FeedException(ErrorCode.LIKE_ALREADY_EXISTS, "이미 활성화된 좋아요입니다");
        }

        dailyLike.reactivate();

        log.info("좋아요 재등록 완료: likeId={}", likeId);
        return DailyLikeResponseDTO.from(dailyLike);
    }

    /**
     * 사용자가 특정 장소에 누른 좋아요들 (이력 확인용)
     */
    public List<DailyLikeResponseDTO> getUserPlaceLikes(UUID userId, Long placeId) {
        List<DailyLike> likes = dailyLikeRepository.findByUserIdAndPlaceId(userId, placeId);
        return likes.stream()
                .filter(DailyLike::isActive)
                .map(DailyLikeResponseDTO::from)
                .collect(Collectors.toList());
    }

    private String maskUserId(UUID userId) {
        String value = userId.toString();
        return value.substring(0, 8) + "****";
    }
}
