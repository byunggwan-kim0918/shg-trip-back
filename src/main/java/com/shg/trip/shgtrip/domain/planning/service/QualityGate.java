package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary.QualityStatus;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Verdict;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 저장 관문. 저장할 일정 엔티티를 판정해 HARD면 막고 CORE면 조정 필요로 표시한다. 편집 뒤에도 같은 기준으로 다시 판정한다.
 * 판정 대상은 매퍼가 장소를 찾지 못한 스텝을 버린 뒤의 실제 저장 객체다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityGate {

    /**
     * @param rules        판정 입력
     * @param fixedNotices 판정으로 다시 만들 수 없는 안내(운전면허·못 찾은 장소). 편집 뒤에도 그대로 둔다
     */
    public record PlanContext(ItineraryRules.Context rules, List<String> fixedNotices) {
        public PlanContext {
            fixedNotices = fixedNotices == null ? List.of() : List.copyOf(fixedNotices);
        }
    }

    /** HARD 판정. 저장 트랜잭션이 롤백되고 생성은 쿼터를 쓰지 않은 실패로 끝난다. */
    public static final class Rejected extends BusinessException {
        private final transient Verdict verdict;
        private final transient QualityNotices.Rejection reason;

        Rejected(Verdict verdict) {
            this(verdict, QualityNotices.rejection(verdict, null));
        }

        Rejected(Verdict verdict, ItineraryRules.Context ctx) {
            this(verdict, QualityNotices.rejection(verdict, ctx));
        }

        private Rejected(Verdict verdict, QualityNotices.Rejection reason) {
            super(ErrorCode.QUALITY_GATE_FAILED, rejectionMessage(reason, null));
            this.verdict = verdict;
            this.reason = reason;
        }

        public Verdict verdict() {
            return verdict;
        }

        public QualityNotices.Rejection reason() {
            return reason;
        }
    }

    /**
     * 저장하지 않은 생성의 사용자 문구. {@link ErrorCode#QUALITY_GATE_FAILED} 기본 문구와 같은 순서로 쓴다.
     *
     * @param reason      판정 사유. 판정 밖의 거부(구조 검증)는 null
     * @param limitNotice 미차감 한도에 닿아 지금 다시 만들 수 없을 때의 안내. 아니면 null
     */
    public static String rejectionMessage(QualityNotices.Rejection reason, String limitNotice) {
        String cause = reason == null ? "이번에는 조건에 맞는 일정을 완성하지 못해 저장하지 않았어요." : reason.cause();
        String next = limitNotice != null ? limitNotice : reason == null ? "다시 만들어 주세요." : reason.action();
        return cause + " " + next + " 30일 생성 횟수는 차감되지 않았어요.";
    }

    private final ObjectMapper objectMapper;

    /** @return 판정. 판정 자체가 실패하면 null이고, 일정은 생성 때 안내를 그대로 두고 OK로 저장된다 */
    public Verdict admit(Itinerary itinerary, PlanContext ctx) {
        Verdict verdict;
        List<String> notices;
        try {
            verdict = SavedItineraryFacts.evaluate(itinerary, ctx.rules());
            notices = mergedNotices(verdict, ctx);
        } catch (RuntimeException e) {
            // 판정 버그 하나로 이미 비용을 쓴 생성 결과를 버리지 않는다.
            log.error("저장 관문 판정 실패, 판정 없이 저장", e);
            itinerary.applyQuality(QualityStatus.OK, itinerary.getQualityNotices(), write(ctx));
            return null;
        }
        List<String> messages = verdict.violations().stream().map(ItineraryRules.Violation::message).toList();
        if (verdict.hard()) {
            log.warn("저장 관문 v{} 거부: {}", verdict.version(), messages);
            throw new Rejected(verdict, ctx.rules());
        }
        QualityStatus status = verdict.core() ? QualityStatus.NEEDS_ADJUSTMENT : QualityStatus.OK;
        itinerary.applyQuality(status, notices, write(ctx));
        log.info("저장 관문 v{}: {} {}", verdict.version(), status, messages);
        return verdict;
    }

    /**
     * 판정 입력이 없는 일정(관문 이전에 만든 일정)은 생성 때 안내를 그대로 둔다.
     * 판정 입력을 읽지 못하거나 판정이 실패해도 편집은 막지 않고 상태·안내만 그대로 둔다.
     */
    public void reflectEdit(Itinerary itinerary) {
        PlanContext ctx = read(itinerary);
        if (ctx == null) return;
        try {
            Verdict verdict = SavedItineraryFacts.evaluate(itinerary, ctx.rules());
            itinerary.reflectEdit(mergedNotices(verdict, ctx), !verdict.hard() && !verdict.core());
        } catch (RuntimeException e) {
            log.error("편집 뒤 판정 실패 itineraryId={}", itinerary.getId(), e);
        }
    }

    /** 판정 안내 뒤에 고정 안내를 붙인다. 생성 중 미리보기도 같은 순서를 써야 화면 안내가 저장 뒤와 같다. */
    static List<String> mergedNotices(Verdict verdict, PlanContext ctx) {
        List<String> out = new ArrayList<>(QualityNotices.of(verdict, ctx.rules()));
        for (String n : ctx.fixedNotices()) {
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    private String write(PlanContext ctx) {
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("판정 입력 기록 실패: {}", e.getMessage());
            return null;
        }
    }

    private PlanContext read(Itinerary itinerary) {
        if (itinerary.getPlanContext() == null) return null;
        try {
            return objectMapper.readValue(itinerary.getPlanContext(), PlanContext.class);
        } catch (Exception e) {
            log.warn("판정 입력 읽기 실패 itineraryId={}: {}", itinerary.getId(), e.getMessage());
            return null;
        }
    }
}
