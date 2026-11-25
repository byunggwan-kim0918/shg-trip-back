package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.integrity.PlaceFingerprints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceInvalidationJobTest {

    private static final BigDecimal LAT = new BigDecimal("33.59001");
    private static final BigDecimal LNG = new BigDecimal("130.40001");

    private static String enrichFp(String name, String category) {
        return PlaceFingerprints.enrichInput(name, category, "Fukuoka", "JP");
    }

    private static String textFp(String name, String category) {
        return PlaceFingerprints.embeddingText(EmbeddingTextBuilder.buildText(Place.builder().name(name)
                .category(category).region("Fukuoka").build()));
    }

    private static PlaceInvalidationJob.Row row(long id, String name, String category, String enrichFp,
                                                String textFp, String factsFp) {
        return new PlaceInvalidationJob.Row(id, name, null, category, "Fukuoka", "JP", LAT, LNG, null, null,
                true, true, enrichFp, textFp, factsFp != null, factsFp);
    }

    @Test
    @DisplayName("지문이 비어 있으면 채우기만 하고, 적재 전(adopt)에는 달라도 비우지 않는다")
    void stampsMissingAndAdoptNeverInvalidates() {
        var fresh = row(1, "一蘭", "Dining and Drinking > Restaurant", null, null, null);
        var changed = row(2, "一蘭", "Dining and Drinking > Restaurant", "old", "old", null);

        var d = PlaceInvalidationJob.decide(List.of(fresh, changed), false);

        assertThat(d.stampEnrich()).extracting(a -> a[1]).containsExactly(1L);
        assertThat(d.stampText()).extracting(a -> a[1]).containsExactly(1L);
        assertThat(d.invalidateEnrich()).isEmpty();
        assertThat(d.invalidateEmbedding()).isEmpty();
    }

    @Test
    @DisplayName("보강 입력이 바뀌면 보강을 비우고, 그 행은 임베딩 지문을 따로 보지 않는다")
    void enrichInputChangeInvalidatesEnrichment() {
        String category = "Dining and Drinking > Restaurant";
        var renamed = row(1, "新饭店", category, enrichFp("饭店", category), textFp("饭店", category), null);

        var d = PlaceInvalidationJob.decide(List.of(renamed), true);

        assertThat(d.invalidateEnrich()).containsExactly(1L);
        assertThat(d.invalidateEmbedding()).isEmpty();
    }

    @Test
    @DisplayName("보강 입력은 그대로이고 임베딩 텍스트만 바뀌면 임베딩만 비운다")
    void textChangeInvalidatesEmbeddingOnly() {
        String category = "Dining and Drinking > Restaurant";
        var row = row(1, "一蘭", category, enrichFp("一蘭", category), "stale-text", null);

        var d = PlaceInvalidationJob.decide(List.of(row), true);

        assertThat(d.invalidateEnrich()).isEmpty();
        assertThat(d.invalidateEmbedding()).containsExactly(1L);
    }

    @Test
    @DisplayName("나라 이름 태그가 있어도 임베딩 배치와 같은 텍스트로 지문을 계산해 임베딩을 비우지 않는다")
    void countryTagDoesNotInvalidateEmbedding() {
        String category = "Dining and Drinking > Restaurant";
        List<String> tags = List.of("라멘", "일본");
        String batchFp = PlaceFingerprints.embeddingText(EmbeddingTextBuilder.buildText(Place.builder().name("一蘭")
                .category(category).region("Fukuoka").country("JP").tags(tags).build()));
        var row = new PlaceInvalidationJob.Row(1, "一蘭", null, category, "Fukuoka", "JP", LAT, LNG, tags, null,
                true, true, enrichFp("一蘭", category), batchFp, false, null);

        var d = PlaceInvalidationJob.decide(List.of(row), true);

        assertThat(d.invalidateEmbedding()).isEmpty();
    }

    @Test
    @DisplayName("변화가 없으면 아무것도 하지 않는다")
    void unchangedRowIsLeftAlone() {
        String category = "Dining and Drinking > Restaurant";
        String facts = PlaceFingerprints.factsInput("一蘭", category, LAT, LNG);
        var row = row(1, "一蘭", category, enrichFp("一蘭", category), textFp("一蘭", category), facts);

        var d = PlaceInvalidationJob.decide(List.of(row), true);

        assertThat(d.stampEnrich()).isEmpty();
        assertThat(d.stampText()).isEmpty();
        assertThat(d.stampFacts()).isEmpty();
        assertThat(d.invalidateEnrich()).isEmpty();
        assertThat(d.invalidateEmbedding()).isEmpty();
        assertThat(d.invalidateFacts()).isEmpty();
    }

    @Test
    @DisplayName("장소 사실 지문은 좌표가 약 100m 안에서 바뀌면 같고, 그보다 멀거나 이름이 바뀌면 다르다")
    void factsFollowNameAndCoarseCoordinates() {
        String category = "Landmarks and Outdoors > Park";
        String facts = PlaceFingerprints.factsInput("大濠公園", category, new BigDecimal("33.58601"),
                new BigDecimal("130.37601"));
        assertThat(PlaceFingerprints.factsInput("大濠公園", category, new BigDecimal("33.58649"),
                new BigDecimal("130.37649"))).isEqualTo(facts);
        assertThat(PlaceFingerprints.factsInput("大濠公園", category, new BigDecimal("33.59001"),
                new BigDecimal("130.37601"))).isNotEqualTo(facts);
        assertThat(PlaceFingerprints.factsInput("大濠公園 日本庭園", category, new BigDecimal("33.58601"),
                new BigDecimal("130.37601"))).isNotEqualTo(facts);
    }
}
