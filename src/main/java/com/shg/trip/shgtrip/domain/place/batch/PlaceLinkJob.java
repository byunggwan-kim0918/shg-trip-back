package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.Link;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.LinkFacts;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkResolver.Verdict;
import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.PairPlace;
import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.Relation;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.LinkRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairClass;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PlaceRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.VerdictRow;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 가까운 장소 행 쌍을 같은 곳·딸린 일부·다른 곳으로 판정해 남기고, 행마다 합쳐지는 대표 행을 기록한다.
 * 판정은 입력 지문이 바뀐 쌍만 다시 묻고, 대표 행 기록은 매번 지금 대상인 쌍의 판정으로 다시 계산한다.
 */
@Slf4j
@Component
@Profile("batch")
public class PlaceLinkJob {

    /** 출처별 같은 호텔·공원 좌표가 100m 넘게 다르기도 하다. */
    static final int MAX_PAIR_METERS = PlaceLinkRepository.MAX_PAIR_METERS;
    /** 이름 근거가 없는 관광지 쌍은 이 거리 안만 묻는다. 라벨 표본 기준 더 먼 쌍에는 같은 곳이 없다. */
    static final int ATTRACTION_NAMELESS_METERS = 40;
    /** 같은 가게의 출처별 행은 수십 m 안에 있다. */
    static final int DINING_PAIR_METERS = 60;
    private static final double MAX_FAILURE_RATIO = 0.2;
    static final String BASIS_RULE = "RULE";
    static final String BASIS_LLM = "LLM";

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\s*[(（\\[][^()（）\\[\\]]*[)）\\]]\\s*$");
    private static final Pattern LATIN_TAIL = Pattern.compile("[\\s(（]+[A-Za-z0-9][A-Za-z0-9 .,'’&()（）/-]*$");

    private final PlaceLinkRepository links;
    private final AnthropicBatchClient batchClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.batch.model:claude-haiku-5-5}")
    private String model;

    @Value("${batch.place-links.regions:}")
    private String regionsCsv;

    /** 이번 실행에서 새로 물을 쌍 상한. 대상 조건이 잘못돼 쌍이 불어날 때 비용을 막는다. 기본값은 application-batch.yml에 둔다. */
    @Value("${batch.place-links.max-pairs}")
    private int maxPairs;

    @Value("${batch.place-links.chunk-size:2000}")
    private int chunkSize = 2000;

    @Value("${batch.place-links.max-duration:PT6H}")
    private Duration maxDuration = Duration.ofHours(6);

    /** @param sameName 대소문자·띄어쓰기·문장부호만 다른 이름. 묻지 않고 같은 곳으로 남긴다 */
    record Candidate(PairPlace low, PairPlace high, int distanceMeters, String inputKey, boolean sameName) {
    }

    PlaceLinkJob(PlaceLinkRepository links, AnthropicBatchClient batchClient, ObjectMapper objectMapper) {
        this.links = links;
        this.batchClient = batchClient;
        this.objectMapper = objectMapper;
    }

    public void run() {
        if (maxPairs < 1) throw new BatchStageFailed("batch.place-links.max-pairs는 1 이상이어야 한다: " + maxPairs);
        long deadline = System.currentTimeMillis() + maxDuration.toMillis();
        List<String> regions = regionsCsv == null || regionsCsv.isBlank() ? links.findActiveRegions()
                : Arrays.stream(regionsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean canAsk = batchClient.hasApiKey();
        if (!canAsk) log.warn("Anthropic API key가 없어 새 쌍은 묻지 않고 남은 판정으로 대표 행만 다시 계산합니다.");

        Map<String, List<Candidate>> candidatesByRegion = new LinkedHashMap<>();
        List<Candidate> toAsk = new ArrayList<>();
        int failedRegions = 0;
        for (String region : regions) {
            try {
                List<Candidate> candidates = candidates(region);
                candidatesByRegion.put(region, candidates);
                toAsk.addAll(recordSameNames(region, candidates));
            } catch (RuntimeException e) {
                failedRegions++;
                log.error("같은 장소 판정 대상 조회 실패 - region={}: {}", region, e.getMessage(), e);
            }
        }
        if (toAsk.size() > maxPairs) {
            log.warn("새로 물을 쌍 {}개가 상한 {}개를 넘어 {}개는 다음 실행에서 묻는다", toAsk.size(), maxPairs,
                    toAsk.size() - maxPairs);
            toAsk = toAsk.subList(0, maxPairs);
        }
        int asked = canAsk ? toAsk.size() : 0;
        int answered = 0;
        if (canAsk && !toAsk.isEmpty()) {
            try {
                answered = ask(toAsk, deadline);
            } catch (RuntimeException e) {
                log.error("같은 장소 판정 요청·반영 실패, 남은 판정으로 대표 행만 다시 계산: {}", e.getMessage(), e);
            }
        }

        int changed = 0;
        for (Map.Entry<String, List<Candidate>> region : candidatesByRegion.entrySet()) {
            try {
                changed += resolve(region.getKey(), region.getValue());
            } catch (RuntimeException e) {
                failedRegions++;
                log.error("같은 장소 대표 행 기록 실패 - region={}: {}", region.getKey(), e.getMessage(), e);
            }
        }
        log.info("같은 장소 판정 완료 - 지역 {}개, 새로 물은 쌍 {}개, 답 {}개, 합쳐진 행 기록 변경 {}개, 실패 지역 {}개",
                candidatesByRegion.size(), asked, answered, changed, failedRegions);
        if (failedRegions > 0 || asked - answered > asked * MAX_FAILURE_RATIO) {
            throw new BatchStageFailed("같은 장소 판정 실패 - 답 없는 쌍 " + (asked - answered) + "/" + asked
                    + ", 실패 지역 " + failedRegions);
        }
    }

    /** 이름 표기만 다른 쌍은 묻지 않고 같은 곳으로 남긴다. @return 판정이 없거나 입력이 바뀌어 물어야 할 나머지 쌍 */
    private List<Candidate> recordSameNames(String region, List<Candidate> candidates) {
        Map<String, String> knownKeys = new HashMap<>();
        links.findVerdicts(region).forEach(v -> knownKeys.put(pairKey(v.lowId(), v.highId()), v.inputKey()));
        List<Candidate> unjudged = candidates.stream()
                .filter(c -> !c.inputKey().equals(knownKeys.get(pairKey(c.low().id(), c.high().id()))))
                .toList();
        List<VerdictRow> sameNames = unjudged.stream().filter(Candidate::sameName)
                .map(c -> new VerdictRow(c.low().id(), c.high().id(), Relation.SAME.name(), BASIS_RULE, c.inputKey()))
                .toList();
        if (!sameNames.isEmpty()) links.upsertVerdicts(sameNames);
        return unjudged.stream().filter(c -> !c.sameName()).toList();
    }

    private static String pairKey(long lowId, long highId) {
        return lowId + ":" + highId;
    }

    List<Candidate> candidates(String region) {
        List<PairRow> pairs = new ArrayList<>();
        pairs.addAll(links.findNearPairs(region, PairClass.ATTRACTION, MAX_PAIR_METERS));
        int attractions = pairs.size();
        pairs.addAll(links.findNearPairs(region, PairClass.LODGING, MAX_PAIR_METERS));
        pairs.addAll(links.findNearPairs(region, PairClass.DINING, DINING_PAIR_METERS));
        Map<Long, PlaceRow> rows = links.findPlaceRows(pairs.stream()
                .flatMap(p -> Stream.of(p.lowId(), p.highId())).collect(Collectors.toSet()));
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            PairRow pair = pairs.get(i);
            PlaceRow low = rows.get(pair.lowId());
            PlaceRow high = rows.get(pair.highId());
            if (low == null || high == null) continue;
            if (i < attractions && pair.distanceMeters() > ATTRACTION_NAMELESS_METERS && !hasNameEvidence(low, high)) {
                continue;
            }
            PairPlace a = pairPlace(low);
            PairPlace b = pairPlace(high);
            boolean sameName = !compact(low.name()).isEmpty() && compact(low.name()).equals(compact(high.name()));
            out.add(new Candidate(a, b, pair.distanceMeters(), PlacePairJudge.inputKey(a, b, pair.distanceMeters()),
                    sameName));
        }
        return out;
    }

    static boolean hasNameEvidence(PlaceRow a, PlaceRow b) {
        if (a.wikidataId() != null && a.wikidataId().equals(b.wikidataId())) return true;
        String ca = compact(a.name());
        String cb = compact(b.name());
        String ba = bare(a.name());
        String bb = bare(b.name());
        String ka = compact(a.nameKo());
        String kb = compact(b.nameKo());
        if (!ba.isEmpty() && ba.equals(bb)) return true;
        if (!ka.isEmpty() && (ka.equals(cb) || ka.equals(bb) || ka.equals(kb))) return true;
        if (!kb.isEmpty() && (kb.equals(ca) || kb.equals(ba))) return true;
        return startsWithOther(ba, bb) || startsWithOther(bb, ba) || startsWithOther(ca, cb) || startsWithOther(cb, ca);
    }

    private static boolean startsWithOther(String shorter, String longer) {
        return shorter.length() >= 2 && longer.length() > shorter.length() && longer.startsWith(shorter);
    }

    static String compact(String name) {
        return name == null ? "" : NON_WORD.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 한글·한자 이름 뒤의 괄호 설명과 영문 병기를 뗀 이름("용연사(강릉)", "해동용궁사 Haedong Yonggungsa"). */
    static String bare(String name) {
        if (name == null) return "";
        String s = TRAILING_NOTE.matcher(name.strip()).replaceFirst("");
        if (s.chars().anyMatch(ch -> ch >= 0x80)) s = LATIN_TAIL.matcher(s).replaceFirst("");
        return compact(s);
    }

    /** 구역 이름(관광특구)·음식 거리는 검색에 나와도 일정 본 칸에 넣지 않아 대표나 큰 장소로 두면 묶인 곳이 사라진다. */
    static boolean usableAsCandidate(PlaceRow r) {
        return r.searchable() && !PlaceCategoryConstants.isAdministrativeZone(r.name())
                && !PlaceCategoryConstants.isAggregatePoi(r.name(), r.category());
    }

    private static PairPlace pairPlace(PlaceRow r) {
        return new PairPlace(r.id(), r.name(), r.nameKo(), r.category(), r.source(), r.address(), r.wikidataId());
    }

    /** 모든 묶음을 먼저 제출하고 차례로 기다린다. @return 답을 받아 남긴 쌍 수 */
    int ask(List<Candidate> pairs, long deadline) {
        Map<String, Candidate> byCustomId = new LinkedHashMap<>();
        for (Candidate c : pairs) byCustomId.put(customId(c), c);
        List<String> customIds = List.copyOf(byCustomId.keySet());
        List<String> batchIds = new ArrayList<>();
        for (int i = 0; i < customIds.size(); i += chunkSize) {
            List<Map<String, Object>> requests = customIds.subList(i, Math.min(i + chunkSize, customIds.size())).stream()
                    .map(id -> {
                        Candidate c = byCustomId.get(id);
                        return Map.<String, Object>of("custom_id", id,
                                "params", PlacePairJudge.requestParams(c.low(), c.high(), c.distanceMeters(), model));
                    })
                    .toList();
            String batchId = batchClient.submit(requests);
            if (batchId == null) {
                log.error("같은 장소 판정 묶음 제출 실패 - {}쌍", requests.size());
                continue;
            }
            batchIds.add(batchId);
        }
        int answered = 0;
        for (String batchId : batchIds) {
            AnthropicBatchClient.PollResult poll = batchClient.poll(batchId, deadline);
            String jsonl = poll.resultsUrl() == null ? null : batchClient.download(poll.resultsUrl());
            if (jsonl == null) {
                log.error("같은 장소 판정 결과를 받지 못함 - batchId={}, 시간 초과={}", batchId, poll.timedOut());
                continue;
            }
            answered += apply(jsonl, byCustomId);
        }
        log.info("같은 장소 판정 - 물은 쌍 {}개, 묶음 {}개, 답 {}개", pairs.size(), batchIds.size(), answered);
        return answered;
    }

    /** custom_id 형식은 {@code pair_{낮은 id}_{높은 id}_{입력 지문}}이다. */
    static String customId(Candidate c) {
        return "pair_" + c.low().id() + "_" + c.high().id() + "_" + c.inputKey();
    }

    int apply(String jsonl, Map<String, Candidate> byCustomId) {
        List<VerdictRow> verdicts = new ArrayList<>();
        Map<Relation, Integer> counts = new EnumMap<>(Relation.class);
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                JsonNode row = objectMapper.readTree(line);
                Candidate c = byCustomId.get(row.path("custom_id").asText(""));
                if (c == null || !"succeeded".equals(row.path("result").path("type").asText())) continue;
                Relation relation = PlacePairJudge.interpret(row.path("result").path("message"));
                if (relation == null) continue;
                counts.merge(relation, 1, Integer::sum);
                verdicts.add(new VerdictRow(c.low().id(), c.high().id(), relation.name(), BASIS_LLM, c.inputKey()));
            } catch (Exception e) {
                log.warn("같은 장소 판정 결과 행 읽기 실패: {}", e.getMessage());
            }
        }
        if (!verdicts.isEmpty()) links.upsertVerdicts(verdicts);
        log.info("같은 장소 판정 분포: {}", counts);
        return verdicts.size();
    }

    /**
     * 지금 대상인 쌍 중 입력 지문이 맞는 판정만 쓴다. 비활성이 됐거나 역할·좌표·이름이 바뀐 행의 옛 판정이 행을 계속 숨기지 않게 한다.
     *
     * @return 합쳐지는 행 기록이 바뀐 행 수
     */
    int resolve(String region, List<Candidate> candidates) {
        Map<String, String> currentKeys = new HashMap<>();
        candidates.forEach(c -> currentKeys.put(pairKey(c.low().id(), c.high().id()), c.inputKey()));
        List<Verdict> verdicts = links.findVerdicts(region).stream()
                .filter(v -> v.inputKey().equals(currentKeys.get(pairKey(v.lowId(), v.highId()))))
                .map(v -> new Verdict(v.lowId(), v.highId(), Relation.valueOf(v.relation())))
                .toList();
        Set<Long> ids = verdicts.stream().flatMap(v -> Stream.of(v.lowId(), v.highId())).collect(Collectors.toSet());
        Map<Long, LinkFacts> facts = new HashMap<>();
        links.findPlaceRows(ids).values().forEach(r -> facts.put(r.id(), new LinkFacts(r.id(), usableAsCandidate(r),
                r.sitelinks(), "tourapi".equals(r.source()), r.hasImage(), r.enriched(), r.candidate())));
        Map<Long, Link> resolved = PlaceLinkResolver.resolve(verdicts, facts);
        int changed = links.replaceLinks(region, resolved.values().stream()
                .map(l -> new LinkRow(l.placeId(), l.targetId(), l.relation()))
                .toList());
        if (changed > 0) {
            long same = resolved.values().stream().filter(l -> PlaceLinkResolver.SAME.equals(l.relation())).count();
            log.info("같은 장소 대표 행 기록 - region={}, 같은 곳 {}행, 딸린 곳 {}행, 바뀐 행 {}", region, same,
                    resolved.size() - same, changed);
        }
        return changed;
    }
}
