package com.shg.trip.shgtrip.domain.place.vector;

import java.util.List;
import java.util.Map;

/**
 * 벡터 검색 추상화 인터페이스.
 * 향후 Qdrant 등 전용 벡터DB로 전환 시 구현체만 교체.
 */
public interface PlaceVectorSearchService {

    /**
     * 벡터 유사도 검색.
     *
     * @param request 검색 요청 (쿼리 벡터, 필터 조건, 결과 수 제한 포함)
     * @return 유사도 기준 정렬된 검색 결과 목록
     */
    List<VectorSearchResult> search(VectorSearchRequest request);

    /**
     * 단일 장소의 임베딩 저장.
     *
     * @param placeId   장소 ID
     * @param embedding 임베딩 벡터 (1536 dimensions)
     */
    void store(Long placeId, float[] embedding);

    /**
     * 단일 장소의 임베딩 삭제.
     *
     * @param placeId 장소 ID
     */
    void delete(Long placeId);

    /**
     * 임베딩 일괄 저장.
     *
     * @param embeddings 장소 ID → 임베딩 벡터 매핑
     */
    void storeBatch(Map<Long, float[]> embeddings);
}
