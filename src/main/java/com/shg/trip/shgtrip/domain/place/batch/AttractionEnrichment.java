package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 식당이 아닌 장소(관광지·숙소·교통 등)의 태그·설명 보강(v1) 규칙. 식당은 {@link DiningEnrichment}, 해외 문맥은
 * {@link OverseasEnrichment}가 맡는다.
 */
@Slf4j
final class AttractionEnrichment {

    private AttractionEnrichment() {}

    static final int MAX_TAGS = 10;
    /** 관광지 태그는 "유네스코 세계자연유산"처럼 식당 태그보다 길다. */
    private static final int MAX_TAG_LENGTH = 20;

    private static final Set<String> ROLES = Set.of(
            "SIGHT", "ACTIVITY", "SHOPPING", "MARKET", "LODGING", "TRANSIT", "NOT_VISITABLE");

    static EnrichPrompt prompt(Place place) {
        StringBuilder user = new StringBuilder();
        user.append("다음 장소 정보를 분석하여 태그, 설명, 추천 방문 시간대를 생성해주세요.\n\n");
        user.append("장소 정보:\n");
        user.append("- 이름: ").append(place.getName()).append("\n");
        user.append("- 카테고리: ").append(place.getCategory()).append("\n");
        if (place.getRegion() != null) {
            user.append("- 지역: ").append(place.getRegion()).append("\n");
        }
        if (place.getCountry() != null) {
            user.append("- 국가: ").append(place.getCountry()).append("\n");
        }
        if (place.getDescription() != null && !place.getDescription().isBlank()) {
            user.append("- 기존 설명: ").append(place.getDescription()).append("\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("다음 JSON 형식으로만 응답해주세요 (다른 텍스트 없이):\n");
        sb.append("{\n");
        sb.append("  \"tags\": [\"태그1\", \"태그2\", ...],  // 5~10개 관련 태그 (한국어)\n");
        sb.append("  \"description\": \"장소에 대한 2~3문장 설명 (한국어)\",\n");
        sb.append("  \"recommended_time_slots\": [\"morning\", \"afternoon\", \"evening\"],  // 추천 방문 시간대\n");
        sb.append("  \"recommended_duration_minutes\": 90,  // 평균 체류시간(분, 정수)\n");
        sb.append("  \"admission_fee\": 0,  // 입장료(원, 정수). 무료면 0, 유료면 성인 1인 기준 실제 입장료\n");
        sb.append("  \"place_role\": \"SIGHT\",  // 여행 일정에서의 역할(아래 목록 중 하나)\n");
        sb.append("  \"traveler_fame\": 0  // 알려진 정도(0~2, 아래 규칙)\n");
        sb.append("}\n");
        sb.append("\n추천 시간대는 다음 중에서 선택: morning, afternoon, evening, night, all_day");
        sb.append("\n체류시간 가이드: 등산·오름·트레킹 150~240 / 해변·시장 60 / 박물관·미술관 90 / 카페 60 / 식당 70");
        sb.append("\n입장료 가이드: 오름·해변·포구·공원·산책로 등 자연 명소는 대부분 0(무료). "
                + "성산일출봉 5000, 산방산 1000처럼 관리되는 유료 명소만 실제 금액. 식당·카페·숙소는 0.");
        sb.append("\n").append(DescriptionRewrite.ENRICH_RULE);
        sb.append("\nplace_role: SIGHT(명소·자연·유적·박물관·공원) / ACTIVITY(체험·레저·테마파크) / "
                + "SHOPPING(쇼핑몰·백화점·쇼핑거리) / MARKET(전통시장·야시장) / LODGING(숙소) / TRANSIT(공항·역·터미널) / "
                + "NOT_VISITABLE(여행자가 일부러 찾아갈 곳이 아님: 편의점·은행·병원·사무실·주차장·일반 매장·아파트·학교 등)");
        sb.append("\n").append(EnrichOutputs.fameRule());
        return new EnrichPrompt(sb.toString(), user.toString());
    }

    /**
     * 해외 관광지 프롬프트. 한국 프롬프트와 같은 항목에 국가·여행지·주소, 현지 통화 입장료, (비어 있으면) name_ko를 더한다.
     * 입장료 예시가 제주 명소라 해외 행에 쓰면 원화 금액이 나온다.
     */
    static EnrichPrompt overseasPrompt(Place place) {
        OverseasEnrichment.Currency currency = OverseasEnrichment.currencyOf(place.getCountry());
        boolean askNameKo = place.getNameKo() == null;
        StringBuilder user = new StringBuilder();
        user.append("다음 해외 장소 정보를 분석하여 한국인 여행자용 태그, 설명, 추천 방문 시간대를 생성해주세요.\n\n");
        user.append("장소 정보:\n");
        user.append("- 이름: ").append(place.getName()).append("\n");
        user.append("- 카테고리: ").append(place.getCategory()).append("\n");
        user.append(OverseasEnrichment.contextLines(place));
        if (place.getDescription() != null && !place.getDescription().isBlank()) {
            user.append("- 기존 설명: ").append(place.getDescription()).append("\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("다음 JSON 형식으로만 응답해주세요 (다른 텍스트 없이):\n");
        sb.append("{\n");
        if (askNameKo) sb.append("  \"name_ko\": \"...\",  // 한국어 표시 이름(아래 규칙)\n");
        sb.append("  \"tags\": [\"태그1\", \"태그2\", ...],  // 5~10개 관련 태그 (한국어)\n");
        sb.append("  \"description\": \"장소에 대한 2~3문장 설명 (한국어)\",\n");
        sb.append("  \"recommended_time_slots\": [\"morning\", \"afternoon\", \"evening\"],  // 추천 방문 시간대\n");
        sb.append("  \"recommended_duration_minutes\": 90,  // 평균 체류시간(분, 정수)\n");
        sb.append("  \"admission_fee_local\": 0,  // 입장료(").append(currency.label()).append(", 정수). ")
                .append("무료면 0, 유료면 성인 1인 기준 실제 입장료\n");
        sb.append("  \"place_role\": \"SIGHT\",  // 여행 일정에서의 역할(아래 목록 중 하나)\n");
        sb.append("  \"traveler_fame\": 0  // 알려진 정도(0~2, 아래 규칙)\n");
        sb.append("}\n");
        sb.append("\n추천 시간대는 다음 중에서 선택: morning, afternoon, evening, night, all_day");
        sb.append("\n체류시간 가이드: 등산·트레킹 150~240 / 해변·시장 60 / 박물관·미술관 90 / 사찰·신사 60 / 테마파크 300");
        sb.append("\n입장료 가이드: 공원·해변·거리·외관만 보는 곳·대부분의 신사는 0(무료). 관리되는 유료 명소만 ")
                .append(currency.label()).append(" 단위 실제 금액. 식당·카페·숙소는 0.");
        sb.append("\n").append(DescriptionRewrite.ENRICH_RULE);
        sb.append("\nplace_role: SIGHT(명소·자연·유적·박물관·공원) / ACTIVITY(체험·레저·테마파크) / "
                + "SHOPPING(쇼핑몰·백화점·쇼핑거리) / MARKET(전통시장·야시장) / LODGING(숙소) / TRANSIT(공항·역·터미널) / "
                + "NOT_VISITABLE(여행자가 일부러 찾아갈 곳이 아님: 편의점·은행·병원·사무실·주차장·일반 매장·아파트·학교 등)");
        sb.append("\n").append(EnrichOutputs.fameRule());
        if (askNameKo) sb.append("\n").append(OverseasEnrichment.nameKoRule());
        return new EnrichPrompt(sb.toString(), user.toString());
    }

    /**
     * 응답을 장소에 반영한다. 국내 설명에 한자·가나가 섞였거나 태그·설명·역할이 모두 비면 false라 실패 횟수에 넣고 다시 묻는다.
     * 빈 결과를 보강 완료로 찍으면 다시 묻지 않아 그 장소는 태그·설명 없이 남는다.
     */
    static boolean apply(JsonNode root, Place place) {
        boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
        List<String> tags = tags(root.get("tags"));
        JsonNode descNode = root.get("description");
        String description = descNode == null || descNode.isNull() ? null : descNode.asText();
        if (EnrichOutputs.rejectsDescription(place.getId(), place.getName(), overseas, description)) return false;
        String role = role(root.get("place_role"));
        if (tags.isEmpty() && (description == null || description.isBlank()) && role == null) {
            log.warn("보강 응답에 태그·설명·역할이 없음 - placeId={}", place.getId());
            return false;
        }
        List<String> timeSlots = EnrichOutputs.validSlots(EnrichOutputs.textList(root.get("recommended_time_slots")));
        JsonNode durationNode = root.get("recommended_duration_minutes");
        Integer durationMinutes = durationNode != null && durationNode.canConvertToInt() && durationNode.asInt() > 0
                ? durationNode.asInt() : null;
        JsonNode feeNode = root.get("admission_fee");
        // 0(무료)도 유효값이다. 관광공사 상세를 조회한 행은 공식 원문 단계가 입장료를 정해 모델 추정으로 덮지 않는다.
        Integer admissionFee = !overseas && place.getTourapiDetailAt() == null && feeNode != null
                && feeNode.canConvertToInt() && feeNode.asInt() >= 0 ? feeNode.asInt() : null;

        place.enrichWith(tags, description, timeSlots, durationMinutes, admissionFee);
        place.assignRole(role, null);
        place.rateFame(EnrichOutputs.fame(root.get("traveler_fame")));
        if (overseas) {
            OverseasEnrichment.Currency currency = OverseasEnrichment.currencyOf(place.getCountry());
            place.recordAdmissionLocal(OverseasEnrichment.admissionLocal(root.get("admission_fee_local"), currency),
                    currency.code());
            place.fillNameKo(OverseasEnrichment.nameKo(root.get("name_ko")));
        }
        return true;
    }

    /** 임베딩 텍스트와 동선 판정이 태그를 그대로 읽어, 빈 값·"#"·중복이 남으면 같은 낱말이 두 번 무게를 받는다. */
    static List<String> tags(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : EnrichOutputs.textList(node)) {
            if (out.size() >= MAX_TAGS) break;
            String tag = EnrichOutputs.normalizeTag(raw, MAX_TAG_LENGTH);
            if (tag != null) out.add(tag);
        }
        return List.copyOf(out);
    }

    /** 프롬프트가 허용하는 역할 값만 받는다. 모르는 값이면 null(일정 로직이 카테고리로 추정). */
    static String role(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").trim().toUpperCase(Locale.ROOT);
        return ROLES.contains(v) ? v : null;
    }
}
