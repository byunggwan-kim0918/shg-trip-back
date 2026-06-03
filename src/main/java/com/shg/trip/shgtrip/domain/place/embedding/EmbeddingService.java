package com.shg.trip.shgtrip.domain.place.embedding;

import java.util.List;

/**
 * 임베딩 생성 추상화 인터페이스.
 * OpenAI text-embedding-3-small 등 구현체를 교체할 수 있도록 추상화.
 */
public interface EmbeddingService {

    /**
     * 단일 텍스트의 임베딩 벡터를 생성한다.
     *
     * @param text 임베딩을 생성할 텍스트
     * @return 임베딩 벡터 (1536 dimensions)
     */
    float[] embed(String text);

    /**
     * 여러 텍스트의 임베딩 벡터를 배치로 생성한다.
     *
     * @param texts 임베딩을 생성할 텍스트 목록
     * @return 각 텍스트에 대응하는 임베딩 벡터 목록 (입력 순서 유지)
     */
    List<float[]> embedBatch(List<String> texts);
}
