package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.integrity.PlaceFingerprints;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 장소 묶음 하나의 임베딩 텍스트를 만들어 저장한다. 월간 임베딩 배치와 승격 장소 즉시 보강이 같은 텍스트·지문을 쓴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceEmbedder {

    private final PlaceRepository placeRepository;
    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    /**
     * @param failedIds 임베딩을 저장하지 못한 장소 ID. 기존 임베딩은 그대로 남는다
     * @param apiFailed 임베딩 API·저장이 실패했다. 텍스트를 못 만든 행만 실패한 경우는 false다
     */
    public record Result(int success, int failed, List<Long> failedIds, boolean apiFailed) {
    }

    public Result embed(List<Place> places) {
        List<String> texts = new ArrayList<>();
        List<Place> validPlaces = new ArrayList<>();
        List<Long> textBuildFailedIds = new ArrayList<>();

        for (Place place : places) {
            try {
                String text = EmbeddingTextBuilder.buildText(place);
                texts.add(text);
                validPlaces.add(place);
            } catch (Exception e) {
                log.warn("텍스트 생성 실패 - placeId={}, name={}: {}",
                        place.getId(), place.getName(), e.getMessage());
                textBuildFailedIds.add(place.getId());
            }
        }

        if (validPlaces.isEmpty()) {
            return new Result(0, places.size(), allIds(places), false);
        }

        List<float[]> embeddings;
        try {
            embeddings = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            log.error("배치 임베딩 생성 API 호출 실패: {}", e.getMessage(), e);
            return new Result(0, places.size(), allIds(places), true);
        }

        if (embeddings.size() != validPlaces.size()) {
            log.error("임베딩 결과 수 불일치 - 요청: {}건, 응답: {}건",
                    validPlaces.size(), embeddings.size());
            return new Result(0, places.size(), allIds(places), true);
        }

        Map<Long, float[]> embeddingMap = new HashMap<>();
        for (int i = 0; i < validPlaces.size(); i++) {
            embeddingMap.put(validPlaces.get(i).getId(), embeddings.get(i));
        }

        try {
            placeVectorSearchService.storeBatch(embeddingMap);
            placeRepository.stampEmbeddingTextFingerprints(
                    validPlaces.stream().map(p -> String.valueOf(p.getId())).collect(Collectors.joining(",")),
                    texts.stream().map(PlaceFingerprints::embeddingText).collect(Collectors.joining(",")));
        } catch (Exception e) {
            log.error("임베딩 일괄 저장 실패: {}", e.getMessage(), e);
            return new Result(0, places.size(), allIds(places), true);
        }

        int failed = places.size() - validPlaces.size();
        return new Result(validPlaces.size(), failed, textBuildFailedIds, false);
    }

    private static List<Long> allIds(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }
}
