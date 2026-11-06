package com.shg.trip.shgtrip.domain.place.vector;

import java.util.List;
import java.util.Map;

public interface PlaceVectorSearchService {

    /** @return 유사도 내림차순 */
    List<VectorSearchResult> search(VectorSearchRequest request);

    /** @param embedding 1536차원 벡터 */
    void store(Long placeId, float[] embedding);

    void delete(Long placeId);

    void storeBatch(Map<Long, float[]> embeddings);
}
