package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.Link;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.LinkFacts;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.Verdict;
import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.Relation;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceLinkResolverTest {

    private static LinkFacts plain(long id) {
        return new LinkFacts(id, true, 0, false, false, true, false);
    }

    private static LinkFacts famous(long id, int sitelinks) {
        return new LinkFacts(id, true, sitelinks, false, true, true, true);
    }

    private static Map<Long, LinkFacts> facts(LinkFacts... rows) {
        Map<Long, LinkFacts> out = new HashMap<>();
        for (LinkFacts f : rows) out.put(f.id(), f);
        return out;
    }

    private static Verdict same(long a, long b) {
        return new Verdict(Math.min(a, b), Math.max(a, b), Relation.SAME);
    }

    /** child가 parent에 딸린 일부. */
    private static Verdict part(long child, long parent) {
        return child < parent ? new Verdict(child, parent, Relation.LOW_IN_HIGH)
                : new Verdict(parent, child, Relation.HIGH_IN_LOW);
    }

    @Test
    @DisplayName("같은 곳 묶음은 검색에 나오는 행 → 언어판 → 관광공사 순으로 대표를 고르고 나머지가 대표를 가리킨다")
    void sameGroupPointsToRepresentative() {
        LinkFacts tourApi = new LinkFacts(1, true, 16, true, true, true, true);
        LinkFacts foursquare = new LinkFacts(2, true, 16, false, false, true, true);
        LinkFacts renamed = new LinkFacts(3, true, 0, false, false, true, true);
        LinkFacts noEmbedding = new LinkFacts(4, false, 30, false, true, false, false);

        Map<Long, Link> links = PlaceLinkResolver.resolve(List.of(same(1, 2), same(2, 3), same(3, 4)),
                facts(tourApi, foursquare, renamed, noEmbedding));

        assertThat(links.values()).containsExactlyInAnyOrder(
                new Link(2, 1, "SAME"), new Link(3, 1, "SAME"), new Link(4, 1, "SAME"));
    }

    @Test
    @DisplayName("딸린 일부는 큰 장소 묶음의 대표를 가리키고, 딸린 쪽 묶음 전체가 함께 간다")
    void partFollowsParentRepresentative() {
        Map<Long, Link> links = PlaceLinkResolver.resolve(
                List.of(same(10, 11), part(20, 11), same(20, 21)),
                facts(famous(10, 5), plain(11), plain(20), plain(21)));

        assertThat(links.values()).containsExactlyInAnyOrder(
                new Link(11, 10, "SAME"), new Link(20, 10, "PART"), new Link(21, 10, "PART"));
    }

    @Test
    @DisplayName("딸린 쪽이 더 알려졌으면(공원 안의 타워) 둘 다 남긴다")
    void moreFamousChildStays() {
        Map<Long, Link> links = PlaceLinkResolver.resolve(List.of(part(2, 1)), facts(famous(1, 7), famous(2, 16)));

        assertThat(links).isEmpty();
    }

    @Test
    @DisplayName("일부의 일부는 맨 위 큰 장소를 가리키고, 가리키는 행은 다시 합쳐지지 않는다")
    void partChainFlattens() {
        Map<Long, Link> links = PlaceLinkResolver.resolve(List.of(part(3, 2), part(2, 1)),
                facts(famous(1, 9), plain(2), plain(3)));

        assertThat(links.values()).containsExactlyInAnyOrder(new Link(2, 1, "PART"), new Link(3, 1, "PART"));
    }

    @Test
    @DisplayName("한 곳이 서로 다른 두 장소의 일부라는 판정이나 서로가 서로의 일부라는 판정은 따르지 않는다")
    void conflictingPartsIgnored() {
        assertThat(PlaceLinkResolver.resolve(List.of(part(3, 1), part(3, 2)), facts(plain(1), plain(2), plain(3))))
                .isEmpty();
        assertThat(PlaceLinkResolver.resolve(List.of(part(1, 2), part(2, 1)), facts(plain(1), plain(2))))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 곳 판정과 사실이 없는 행이 낀 판정은 건너뛰고, 너무 큰 같은 곳 묶음은 합치지 않는다")
    void skipsDifferentMissingAndOversizedGroups() {
        assertThat(PlaceLinkResolver.resolve(List.of(new Verdict(1, 2, Relation.DIFFERENT), same(1, 99)),
                facts(plain(1), plain(2)))).isEmpty();

        int size = PlaceLinkResolver.MAX_SAME_GROUP + 1;
        List<Verdict> chain = LongStream.range(1, size).mapToObj(i -> same(i, i + 1)).toList();
        Map<Long, LinkFacts> rows = new HashMap<>();
        LongStream.rangeClosed(1, size).forEach(i -> rows.put(i, plain(i)));
        assertThat(PlaceLinkResolver.resolve(chain, rows)).isEmpty();
    }

    @Test
    @DisplayName("같은 곳 사슬 안의 두 행을 다른 곳으로 판정했으면(본점과 다른 지점) 그 묶음은 합치지 않는다")
    void contradictedGroupIgnored() {
        Map<Long, Link> links = PlaceLinkResolver.resolve(
                List.of(same(1, 2), same(1, 3), new Verdict(2, 3, Relation.DIFFERENT), same(5, 6)),
                facts(plain(1), plain(2), plain(3), plain(5), plain(6)));

        assertThat(links.values()).containsExactly(new Link(6, 5, "SAME"));
    }

    @Test
    @DisplayName("검색에 나오지 않는 큰 장소에는 딸린 쪽을 합치지 않는다")
    void partNeedsSearchableParent() {
        LinkFacts closedPark = new LinkFacts(1, false, 0, true, false, true, false);

        assertThat(PlaceLinkResolver.resolve(List.of(part(2, 1)), facts(closedPark, plain(2)))).isEmpty();
    }

    private static List<Verdict> verdicts(List<Integer> codes) {
        return codes.stream().map(code -> {
            long a = code % 10;
            long b = (code / 10) % 10;
            if (a == b) b = (b + 1) % 10;
            Relation relation = Relation.values()[code / 100];
            return new Verdict(Math.min(a, b), Math.max(a, b), relation);
        }).toList();
    }

    private static Map<Long, LinkFacts> mixedRows() {
        Map<Long, LinkFacts> rows = new HashMap<>();
        LongStream.range(0, 10).forEach(i -> rows.put(i, new LinkFacts(i, i % 3 != 0, (int) (i % 4), i % 2 == 0,
                false, true, false)));
        return rows;
    }

    @Property
    @Label("어떤 판정 조합이든 가리키는 행은 합쳐지지 않은 다른 행이다")
    void targetsAreNeverMerged(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 399) Integer> codes) {
        Map<Long, Link> links = PlaceLinkResolver.resolve(verdicts(codes), mixedRows());

        for (Link l : links.values()) {
            assertThat(l.targetId()).isNotEqualTo(l.placeId());
            assertThat(links).doesNotContainKey(l.targetId());
        }
    }

    @Property
    @Label("어떤 판정 조합이든 가리키는 행은 후보로 쓰이는 행이다")
    void targetsAreSearchable(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 399) Integer> codes) {
        Map<Long, LinkFacts> rows = mixedRows();

        Map<Long, Link> links = PlaceLinkResolver.resolve(verdicts(codes), rows);

        for (Link l : links.values()) assertThat(rows.get(l.targetId()).searchable()).isTrue();
    }

    @Property
    @Label("다른 곳으로 판정한 두 행은 같은 대표로 묶이지 않는다")
    void differentPairsNeverShareSameRepresentative(
            @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 399) Integer> codes) {
        List<Verdict> verdicts = verdicts(codes);

        Map<Long, Link> links = PlaceLinkResolver.resolve(verdicts, mixedRows());

        for (Verdict v : verdicts) {
            if (v.relation() != Relation.DIFFERENT) continue;
            Link low = links.get(v.lowId());
            Link high = links.get(v.highId());
            long lowTarget = low != null && low.relation().equals("SAME") ? low.targetId() : v.lowId();
            long highTarget = high != null && high.relation().equals("SAME") ? high.targetId() : v.highId();
            assertThat(lowTarget).isNotEqualTo(highTarget);
        }
    }
}
