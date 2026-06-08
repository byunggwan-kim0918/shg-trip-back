package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fallback 경로에서 생성된 장소의 임베딩을 비동기로 생성하는 서비스.
 * <p>
 * Fallback 경로(기존 LLM 직접 생성)에서 새로 저장된 장소에 대해
 * 임베딩을 비동기적으로 생성하여 Place_Pool에 저장한다.
 * 이를 통해 다음 요청 시 해당 장소가 벡터 검색 후보로 활용될 수 있다.
 * <p>
 * 동작 방식:
 * - @Async로 호출자를 블로킹하지 않음
 * - 이미 임베딩이 있는 장소는 건너뜀
 * - 개별 장소 실패 시 로그만 남기고 나머지 계속 처리 (best-effort)
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackPlaceEmbeddingService {

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    /**
     * Fallback 경로에서 저장된 장소 목록에 대해 비동기로 임베딩을 생성한다.
     * <p>
     * - 이미 임베딩이 있는 장소(embedding != null)는 건너뜀
     * - 각 장소의 임베딩 생성/저장 실패는 로그만 남기고 계속 진행
     *
     * @param places Fallback 경로에서 생성되어 DB에 저장된 장소 목록
     */
    @Async("planningExecutor")
    public void generateEmbeddingsAsync(List<Place> places) {
        if (places == null || places.isEmpty()) {
            return;
        }

        log.info("Fallback 장소 임베딩 비동기 생성 시작 - {}건", places.size());

        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (Place place : places) {
            if (place.getEmbedding() != null) {
                skipped++;
                continue;
            }

            try {
                String text = EmbeddingTextBuilder.buildText(place);
                float[] embedding = embeddingService.embed(text);
                placeVectorSearchService.store(place.getId(), embedding);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("Fallback 장소 임베딩 생성 실패 - placeId={}, name={}: {}",
                        place.getId(), place.getName(), e.getMessage());
            }
        }

        log.info("Fallback 장소 임베딩 비동기 생성 완료 - 성공: {}건, 건너뜀: {}건, 실패: {}건",
                success, skipped, failed);
    }
}
