package com.shg.trip.shgtrip.domain.place.embedding;

import java.util.List;

public interface EmbeddingService {

    /** @return 1536차원 벡터 */
    float[] embed(String text);

    /** @return 입력 순서와 같은 순서의 벡터 목록 */
    List<float[]> embedBatch(List<String> texts);
}
