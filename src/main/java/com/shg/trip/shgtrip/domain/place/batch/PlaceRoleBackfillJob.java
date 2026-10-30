package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.CandidatePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 비방문이 확실한 관광 대분류 장소(골프장·주차장·행정구역 등)에 {@code place_role=NOT_VISITABLE}을 기록한다.
 *
 * <p>일정 로직은 역할이 비어 있으면 실행 중에 추정해 버리는데, 그건 벡터 검색이 후보 윈도를 다 채운 뒤라
 * 그만큼 슬롯 후보가 준다. DB에 기록해 두면 검색 SQL이 처음부터 빼고 보강 배치도 이 행에 돈을 쓰지 않는다.
 *
 * <p>기록 범위는 {@link CandidatePolicy#isCertainlyNotVisitable}로 좁힌다. 기록된 행은 보강 대상에서 빠져 다시
 * 판정받지 못하므로, 틀릴 수 있는 추정(도서관·스파·식사도 카페도 아닌 식당)은 쓰지 않는다. LLM·외부 호출 없음.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class PlaceRoleBackfillJob {

    private static final int PAGE_SIZE = 2_000;

    private final PlaceRepository placeRepository;

    public void run() {
        long afterId = 0;
        int scanned = 0;
        int marked = 0;
        while (true) {
            List<Object[]> page = placeRepository.findRoleBackfillTargets(afterId, PAGE_SIZE);
            if (page.isEmpty()) break;
            List<Long> ids = new ArrayList<>();
            for (Object[] row : page) {
                if (CandidatePolicy.isCertainlyNotVisitable((String) row[1], (String) row[2])) {
                    ids.add(((Number) row[0]).longValue());
                }
            }
            if (!ids.isEmpty()) marked += placeRepository.markNotVisitable(ids);
            scanned += page.size();
            afterId = ((Number) page.get(page.size() - 1)[0]).longValue();
        }
        log.info("역할 백필 완료 - 검사 {}건, NOT_VISITABLE 기록 {}건", scanned, marked);
    }
}
