package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * embedding IS NULL인 장소를 조회하여 배치 임베딩을 생성하고 저장하는 작업.
 * <p>
 * 처리 흐름:
 * 1. embedding IS NULL이고 active=true인 장소를 페이징으로 조회
 * 2. 배치 사이즈(기본 100)씩 EmbeddingTextBuilder로 텍스트 생성
 * 3. EmbeddingService.embedBatch로 벡터 생성
 * 4. PlaceVectorSearchService.storeBatch로 DB에 저장
 * 5. 실패한 장소는 건너뛰고 계속 처리
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class EmbeddingBatchJob {

    private final PlaceRepository placeRepository;
    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    @Value("${batch.embedding.batch-size:100}")
    private int batchSize;

    /**
     * 임베딩 배치 생성을 실행한다.
     */
    public void execute() {
        log.info("EmbeddingBatchJob 시작 - batchSize={}", batchSize);

        int totalProcessed = 0;
        int totalFailed = 0;
        int pageNumber = 0;

        while (true) {
            Page<Place> page = placeRepository.findByEmbeddingIsNullAndActiveTrue(
                    PageRequest.of(pageNumber, batchSize));

            if (page.isEmpty()) {
                break;
            }

            List<Place> places = page.getContent();
            log.info("배치 {} 처리 중 - {}건 (전체 미임베딩: {}건)",
                    pageNumber + 1, places.size(), page.getTotalElements());

            int[] result = processBatch(places);
            totalProcessed += result[0];
            totalFailed += result[1];

            // 처리된 장소는 embedding이 채워지므로 항상 page 0을 조회
            // 실패한 장소가 있으면 다음 페이지로 이동
            if (result[1] > 0) {
                pageNumber++;
            }
            // pageNumber는 실패한 항목이 없으면 0을 유지 (이미 임베딩이 저장됨)

            if (!page.hasNext() && result[1] == 0) {
                break;
            }
        }

        log.info("EmbeddingBatchJob 완료 - 처리: {}건, 실패: {}건", totalProcessed, totalFailed);
    }

    /**
     * 장소 배치를 처리하여 임베딩을 생성하고 저장한다.
     *
     * @param places 임베딩을 생성할 장소 목록
     * @return [성공 건수, 실패 건수]
     */
    int[] processBatch(List<Place> places) {
        List<String> texts = new ArrayList<>();
        List<Place> validPlaces = new ArrayList<>();

        // 1. 텍스트 생성 (실패 시 해당 장소 건너뛰기)
        for (Place place : places) {
            try {
                String text = EmbeddingTextBuilder.buildText(place);
                texts.add(text);
                validPlaces.add(place);
            } catch (Exception e) {
                log.warn("텍스트 생성 실패 - placeId={}, name={}: {}",
                        place.getId(), place.getName(), e.getMessage());
            }
        }

        if (validPlaces.isEmpty()) {
            return new int[]{0, places.size()};
        }

        // 2. 배치 임베딩 생성
        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            log.error("배치 임베딩 생성 API 호출 실패: {}", e.getMessage(), e);
            return new int[]{0, places.size()};
        }

        if (embeddings.size() != validPlaces.size()) {
            log.error("임베딩 결과 수 불일치 - 요청: {}건, 응답: {}건",
                    validPlaces.size(), embeddings.size());
            return new int[]{0, places.size()};
        }

        // 3. 일괄 저장
        Map<Long, float[]> embeddingMap = new HashMap<>();
        for (int i = 0; i < validPlaces.size(); i++) {
            embeddingMap.put(validPlaces.get(i).getId(), embeddings.get(i));
        }

        try {
            placeVectorSearchService.storeBatch(embeddingMap);
        } catch (Exception e) {
            log.error("임베딩 일괄 저장 실패: {}", e.getMessage(), e);
            return new int[]{0, places.size()};
        }

        int failed = places.size() - validPlaces.size();
        return new int[]{validPlaces.size(), failed};
    }
}
