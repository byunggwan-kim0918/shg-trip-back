package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 평가에서 유료 임베딩 API를 부르지 않는다(--eval.offline-embeddings=true). 질의 대신 DB 장소 임베딩의 평균을 쓴다.
 * 근처 검색(숙소·식당·예비 장소)이 역할별 범주 필터로 후보를 내지만 순위는 실제 질의와 달라, 같은 설정으로 돌린 결과끼리만
 * 견준다.
 */
@Component
@Primary
@Profile("eval")
@ConditionalOnProperty(name = "eval.offline-embeddings", havingValue = "true")
class OfflineEmbeddings implements EmbeddingService {

    private final JdbcTemplate jdbc;
    private volatile float[] centroid;

    OfflineEmbeddings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public float[] embed(String text) {
        return centroid();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) out.add(centroid());
        return out;
    }

    private float[] centroid() {
        float[] c = centroid;
        if (c != null) return c;
        synchronized (this) {
            if (centroid == null) {
                String v = jdbc.queryForObject("SELECT avg(embedding::vector)::text FROM places "
                        + "WHERE embedding IS NOT NULL AND active", String.class);
                if (v == null) throw new IllegalStateException("no place embeddings for offline eval");
                String[] parts = v.substring(1, v.length() - 1).split(",");
                float[] out = new float[parts.length];
                for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
                centroid = out;
            }
            return centroid;
        }
    }
}
