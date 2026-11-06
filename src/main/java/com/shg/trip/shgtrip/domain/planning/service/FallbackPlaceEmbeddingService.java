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
 * Fallback 경로에서 새로 저장된 장소를 다음 요청부터 벡터 검색 후보로 쓰려고 임베딩한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackPlaceEmbeddingService {

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    /**
     * 장소별 실패는 로그만 남기고 나머지를 계속 처리한다(best-effort).
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
