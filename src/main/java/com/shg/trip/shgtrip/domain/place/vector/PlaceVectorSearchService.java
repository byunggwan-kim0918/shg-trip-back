package com.shg.trip.shgtrip.domain.place.vector;

import java.util.List;
import java.util.Map;

public interface PlaceVectorSearchService {

    /** @return 유사도 내림차순 */
    List<VectorSearchResult> search(VectorSearchRequest request);

    /** 지역마다 Wikidata 항목이 있는 관광 계열 장소를 언어판 수 내림차순으로. 유사도는 queryVector 기준. */
    List<VectorSearchResult> findPopular(float[] queryVector, String country, List<String> regions, int limitPerRegion);

    /** @param embedding openai.embedding-dimensions 차원 벡터 */
    void store(Long placeId, float[] embedding);

    void delete(Long placeId);

    void storeBatch(Map<Long, float[]> embeddings);

    /** 대량 적재 전에 근사 검색 인덱스를 내린다. 그동안 검색은 정확 탐색이라 느리다. */
    void dropAnnIndex();

    /** 이미 있으면 아무것도 하지 않는다. */
    void createAnnIndex();
}
