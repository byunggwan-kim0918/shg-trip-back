package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.Relation;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 같은 곳 묶음은 가장 알려진 행 하나만 남기고, 딸린 일부는 큰 장소의 대표 행을 가리키게 한다. 가리키는 행은 늘
 * 스스로 합쳐지지 않은 행이라 읽는 쪽이 한 번만 따라가면 된다.
 */
@Slf4j
final class PlaceLinkResolver {

    private PlaceLinkResolver() {}

    /** 같은 곳 묶음이 이보다 크면 판정이 사슬처럼 이어져 다른 장소까지 묶였을 가능성이 커 합치지 않는다. */
    static final int MAX_SAME_GROUP = 8;
    static final String SAME = "SAME";
    static final String PART = "PART";

    /** @param searchable 후보 검색에 나오고 일정 본 칸에 들어갈 수 있는 행 */
    record LinkFacts(long id, boolean searchable, int sitelinks, boolean tourApi, boolean hasImage, boolean enriched,
                     boolean candidate) {

        int notability() {
            return sitelinks * 10 + (tourApi ? 5 : 0) + (candidate ? 1 : 0);
        }
    }

    record Verdict(long lowId, long highId, Relation relation) {
    }

    /** @param relation {@link #SAME} 또는 {@link #PART} */
    record Link(long placeId, long targetId, String relation) {
    }

    /** 검색에 나오지 않는 행을 대표로 두면 그 장소가 후보에서 통째로 사라진다. */
    static final Comparator<LinkFacts> REPRESENTATIVE = Comparator
            .comparing(LinkFacts::searchable).reversed()
            .thenComparing(Comparator.comparingInt(LinkFacts::sitelinks).reversed())
            .thenComparing(Comparator.comparing(LinkFacts::tourApi).reversed())
            .thenComparing(Comparator.comparing(LinkFacts::hasImage).reversed())
            .thenComparing(Comparator.comparing(LinkFacts::enriched).reversed())
            .thenComparingLong(LinkFacts::id);

    /**
     * @param facts 판정에 나온 행 전부. 없는 행이 낀 판정은 건너뛴다
     * @return 합쳐지는 행만. 대표 행과 판정에 없던 행은 들어 있지 않다
     */
    static Map<Long, Link> resolve(Collection<Verdict> verdicts, Map<Long, LinkFacts> facts) {
        UnionFind same = new UnionFind();
        List<Verdict> parts = new ArrayList<>();
        List<Verdict> differents = new ArrayList<>();
        for (Verdict v : verdicts) {
            if (!facts.containsKey(v.lowId()) || !facts.containsKey(v.highId())) continue;
            same.find(v.lowId());
            same.find(v.highId());
            switch (v.relation()) {
                case SAME -> same.union(v.lowId(), v.highId());
                case LOW_IN_HIGH, HIGH_IN_LOW -> parts.add(v);
                case DIFFERENT -> differents.add(v);
            }
        }

        // 같은 곳 판정이 사슬로 이어져 서로 다르다고 판정한 두 행(본점과 다른 지점)이 한 묶음에 들면 묶음을 믿지 않는다.
        Set<Long> contradicted = new HashSet<>();
        for (Verdict v : differents) {
            if (same.find(v.lowId()) == same.find(v.highId())) contradicted.add(same.find(v.lowId()));
        }

        Map<Long, List<Long>> groups = same.groups();
        Map<Long, Long> representativeOfGroup = new HashMap<>();
        Map<Long, Integer> notabilityOfGroup = new HashMap<>();
        for (Map.Entry<Long, List<Long>> group : groups.entrySet()) {
            List<LinkFacts> members = group.getValue().stream().map(facts::get).sorted(REPRESENTATIVE).toList();
            // 대표가 후보로 쓰이지 않는 행이면 묶음 전체가 후보에서 사라진다.
            if (!members.get(0).searchable()) continue;
            if (members.size() > MAX_SAME_GROUP || contradicted.contains(group.getKey())) {
                log.warn("같은 곳 묶음을 합치지 않음({}행, 다른 곳 판정 {}) - ids={}", members.size(),
                        contradicted.contains(group.getKey()), group.getValue());
                continue;
            }
            representativeOfGroup.put(group.getKey(), members.get(0).id());
            notabilityOfGroup.put(group.getKey(),
                    members.stream().mapToInt(LinkFacts::notability).max().orElse(0));
        }

        // 딸린 쪽이 더 알려졌으면(공원 안의 타워) 그쪽이 찾아가는 대상이라 둘 다 둔다.
        Map<Long, Long> parentGroup = new HashMap<>();
        Set<Long> conflicted = new HashSet<>();
        for (Verdict v : parts) {
            long child = v.relation() == Relation.LOW_IN_HIGH ? v.lowId() : v.highId();
            long parent = v.relation() == Relation.LOW_IN_HIGH ? v.highId() : v.lowId();
            Long childGroup = groupKey(same, representativeOfGroup, child);
            Long parentGroupKey = groupKey(same, representativeOfGroup, parent);
            if (childGroup == null || parentGroupKey == null || childGroup.equals(parentGroupKey)) continue;
            if (notabilityOfGroup.get(childGroup) > notabilityOfGroup.get(parentGroupKey)) continue;
            // 후보로 쓰이지 않는 큰 장소에 합치면 딸린 쪽까지 후보에서 사라진다.
            if (!facts.get(representativeOfGroup.get(parentGroupKey)).searchable()) continue;
            Long previous = parentGroup.putIfAbsent(childGroup, parentGroupKey);
            if (previous != null && !previous.equals(parentGroupKey)) conflicted.add(childGroup);
        }
        conflicted.forEach(parentGroup::remove);

        Map<Long, Link> links = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> group : representativeOfGroup.entrySet()) {
            Long top = topOf(group.getKey(), parentGroup);
            long representative = group.getValue();
            for (long member : groups.get(group.getKey())) {
                if (top != null) {
                    links.put(member, new Link(member, representativeOfGroup.get(top), PART));
                } else if (member != representative) {
                    links.put(member, new Link(member, representative, SAME));
                }
            }
        }
        return links;
    }

    private static Long groupKey(UnionFind same, Map<Long, Long> representativeOfGroup, long id) {
        long root = same.find(id);
        return representativeOfGroup.containsKey(root) ? root : null;
    }

    /** 딸린 일부를 위로 따라가 맨 위 큰 장소 묶음. 딸린 데가 없거나 고리면 null이다. */
    private static Long topOf(long group, Map<Long, Long> parentGroup) {
        Long current = parentGroup.get(group);
        if (current == null) return null;
        Set<Long> seen = new HashSet<>(List.of(group));
        while (parentGroup.containsKey(current)) {
            if (!seen.add(current)) return null;
            current = parentGroup.get(current);
        }
        return current;
    }

    private static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        long find(long x) {
            parent.putIfAbsent(x, x);
            long root = x;
            while (parent.get(root) != root) root = parent.get(root);
            while (parent.get(x) != root) {
                long next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        void union(long a, long b) {
            long ra = find(a);
            long rb = find(b);
            if (ra != rb) parent.put(Math.max(ra, rb), Math.min(ra, rb));
        }

        Map<Long, List<Long>> groups() {
            Map<Long, List<Long>> out = new LinkedHashMap<>();
            for (long id : new ArrayList<>(parent.keySet())) out.computeIfAbsent(find(id), k -> new ArrayList<>()).add(id);
            return out;
        }
    }
}
