package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 장소 한 곳의 태그·설명·역할·한국어 이름 보강(v1) 규칙. 월간 배치와 승격 장소 즉시 보강이 같은 프롬프트·해석·검증을 쓴다.
 * 식당·카페·술집은 {@link DiningEnrichment}, 그 밖은 {@link AttractionEnrichment}, 해외 행은 {@link OverseasEnrichment}를 더한다.
 */
@Slf4j
public final class PlaceEnrichment {

    private PlaceEnrichment() {
    }

    public static EnrichPrompt prompt(Place place) {
        boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
        if (DiningEnrichment.isDiningVenue(place.getCategory())) {
            return DiningEnrichment.buildPrompt(place.getName(), place.getCategory(), place.getDescription(),
                    DiningEnrichment.venueOf(place.getCategory()),
                    DiningEnrichment.isFamous(place.getWikidataSitelinks()),
                    overseas ? OverseasEnrichment.contextLines(place) : null,
                    overseas && place.getNameKo() == null);
        }
        return overseas ? AttractionEnrichment.overseasPrompt(place) : AttractionEnrichment.prompt(place);
    }

    /**
     * 응답을 place에 반영한다. 저장은 호출부가 한다.
     *
     * @return 응답이 JSON이 아니거나(잘림 포함) 규칙에 맞지 않으면 false. 그 밖의 예외는 던져 코드 오류를 장소 실패로 세지 않는다
     */
    public static boolean apply(ObjectMapper objectMapper, Place place, String content) {
        try {
            JsonNode root = objectMapper.readTree(EnrichOutputs.extractJson(content));
            if (!DiningEnrichment.isDiningVenue(place.getCategory())) return AttractionEnrichment.apply(root, place);
            boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
            if (!root.isObject()) {
                log.warn("식당 보강 응답이 JSON 객체가 아님 - placeId={}", place.getId());
                return false;
            }
            Short fame = EnrichOutputs.fame(root.get("traveler_fame"));
            // 모델이 모르는 동네 식당의 설명은 지어낸 내용이다. 해외는 Wikidata에 없는 유명 식당이 많아 알려진 곳 판단을 믿는다.
            boolean describe = DiningEnrichment.isFamous(place.getWikidataSitelinks())
                    || (overseas && fame != null && fame >= 1);
            DiningEnrichment.Result dining = DiningEnrichment.interpret(root,
                    DiningEnrichment.venueOf(place.getCategory()), describe,
                    overseas ? OverseasEnrichment.ownRegionNames(place) : Set.of());
            if (EnrichOutputs.rejectsDescription(place.getId(), place.getName(), overseas, dining.description())) {
                return false;
            }
            place.enrichWith(dining.tags(), dining.description(), dining.timeSlots(), dining.durationMinutes(), null);
            // 카테고리 리프까지 보고 음식 종류를 다시 정한다(태그만으로는 "라멘집"의 면요리가 빠질 수 있다).
            String cuisine = !"MEAL".equals(dining.placeRole()) ? null
                    : overseas ? OverseasEnrichment.cuisineOf(dining.tags(), place.getCategory())
                    : DiningEnrichment.cuisineOf(dining.tags(), place.getCategory());
            place.assignRole(dining.placeRole(), cuisine);
            place.rateFame(fame);
            if (overseas) place.fillNameKo(OverseasEnrichment.nameKo(root.get("name_ko"), place.getName()));
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("보강 데이터 파싱 실패 - placeId={}, error={}", place.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 원천이 준 한국어 이름을 보강 응답과 같은 검사로 본다. 국내 장소는 한국어 표시 이름을 따로 쓰지 않아 그대로 둔다.
     *
     * @return 검사를 통과한 이름. 통과하지 못하면 null
     */
    public static String verifiedNameKo(String nameKo, String originalName, String country) {
        if (!OverseasEnrichment.isOverseas(country)) return nameKo;
        return OverseasEnrichment.nameKoText(nameKo, originalName);
    }
}
