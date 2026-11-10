package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.QuestionKind;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 질문·예시·반응 문구는 승인된 디자인 시안 문구를 따른다. */
public final class QuestionBank {

    static final String EDIT_LEAD = "바꾸고 싶은 내용을 알려주세요";
    static final String FOLLOW_UP_LEAD = "알려준 이야기에서 하나만 더 물어볼게요.";
    static final String OFF_TOPIC_LEAD = "여행 이야기로 돌아가 볼게요.";
    static final String UNDECIDED_LEAD = "괜찮아요. 아직 정하지 않은 부분은 미정으로 남겨둘게요.";
    static final String FOLLOW_UP_DONE_LEAD = "알려준 내용까지 함께 정리해둘게요.";

    private QuestionBank() {}

    private record Copy(String title, String description, String placeholder, List<String> examples) {}

    public static OpenQuestion base(Slot slot, Draft draft, LocalDate today, String lead) {
        Copy c = baseCopy(slot, draft, today);
        return new OpenQuestion(newId(), slot, QuestionKind.BASE, lead, c.title(), c.description(), c.placeholder(),
                c.examples(), false, slot == Slot.ANCHORS ? OpenQuestion.TOOL_NONE : null,
                "BASE:" + slot, null, false);
    }

    public static OpenQuestion edit(Slot slot, Draft draft, LocalDate today, String prefill, boolean returnToReview) {
        Copy c = baseCopy(slot, draft, today);
        return new OpenQuestion(newId(), slot, QuestionKind.EDIT, EDIT_LEAD, c.title(), c.description(),
                c.placeholder(), c.examples(), false, slot == Slot.ANCHORS ? OpenQuestion.TOOL_NONE : null,
                "EDIT:" + slot, prefill, returnToReview);
    }

    public static OpenQuestion followUp(Slot slot, String askKey, String lead, String title, String description,
                                        String placeholder, List<String> examples) {
        return new OpenQuestion(newId(), slot, QuestionKind.FOLLOW_UP, lead, title, description, placeholder,
                examples, true, OpenQuestion.TOOL_UNKNOWN, askKey, null, false);
    }

    /** 모델이 낸 되묻기 문구가 검사를 통과하지 못했을 때 쓰는 항목별 문구. */
    public static OpenQuestion followUpTemplate(Slot slot, String askKey, LocalDate today) {
        return switch (slot) {
            case CITY -> followUp(slot, askKey, FOLLOW_UP_LEAD, "떠오르는 지역이 하나라도 있나요?",
                    "도시나 지역을 알려주면 그곳에 맞춰 준비할 수 있어요. 아직 없다면 미정으로 둘게요.",
                    "예: 부산 쪽으로 생각 중이야", List.of("부산", "제주", "아직 정하지 않았어"));
            case DATES -> followUp(slot, askKey, FOLLOW_UP_LEAD, "날짜나 며칠 정도 갈지 알려줄래요?",
                    "날짜가 아직 없으면 여행 기간만 말해도 괜찮아요.", "예: 2박 3일 정도",
                    List.of(sampleRange(today), "2박 3일 정도", "아직 모르겠어"));
            case COMPANY -> followUp(slot, askKey, FOLLOW_UP_LEAD, "몇 명이서 가나요?",
                    "함께하는 사람 수만 알려줘도 좋아요.", "예: 나까지 4명",
                    List.of("나까지 4명", "둘이 가", "아직 정해지지 않았어"));
            case MOOD -> followUp(slot, askKey, FOLLOW_UP_LEAD, "어떤 순간이 더 끌리나요?",
                    "한 가지만 말해도 좋아요. 특별히 떠오르는 게 없어도 괜찮아요.", "예: 사람 적은 곳에서 쉬고 싶어",
                    List.of("아무 생각 없이 바다 보고 싶어", "맛집 찾아다니고 싶어", "취향은 맡길게"));
            case TRANSPORT -> followUp(slot, askKey, FOLLOW_UP_LEAD, "이동 방법을 조금만 더 알려줄래요?",
                    "차를 이용할지, 주로 걸어 다닐지처럼 편하게 말해주세요.", "편하게 답해주세요",
                    List.of("차 가져갈 거야", "걸어 다니다 멀면 택시 탈래", "아직 정하지 않았어"));
            case ANCHORS -> followUp(slot, askKey, FOLLOW_UP_LEAD, "예약한 날짜와 시간이 어떻게 되나요?",
                    "예약에 맞춰 앞뒤 일정을 잡으려면 시간이 필요해요. 지금 확인하기 어렵다면 미정으로 둘 수 있어요.",
                    "예: 11월 21일 오후 2시야", List.of());
            case EXTRAS -> followUp(slot, askKey, FOLLOW_UP_LEAD, "조금만 더 알려줄래요?",
                    "말하기 어렵다면 미정으로 둘게요.", "편하게 답해주세요", List.of());
        };
    }

    /** 답한 항목에 대한 다음 질문 위 반응 한 줄. 사용자가 말한 사실만 되짚는다. */
    public static String ack(Slot answered, Draft draft) {
        FieldStatus status = draft.slotStatus(answered);
        boolean undecided = status != null && status != FieldStatus.KNOWN;
        if (undecided && answered != Slot.CITY && answered != Slot.DATES && answered != Slot.ANCHORS) {
            String subject = switch (answered) {
                case COMPANY -> "동행은";
                case MOOD -> "취향은";
                case TRANSPORT -> "이동 방법은";
                default -> "그 부분은";
            };
            return switch (status) {
                case DELEGATED -> subject + " 맡겨준 걸로 기억해둘게요.";
                case ANY -> subject + " 상관없는 걸로 기억해둘게요.";
                default -> subject + " 미정으로 남겨둘게요.";
            };
        }
        return switch (answered) {
            case CITY -> {
                String city = draft.value(DraftField.DESTINATION);
                yield city == null || undecided ? "여행지는 아직 미정이군요." : city + " 여행을 생각하고 있군요.";
            }
            case DATES -> undecided ? "날짜는 미정으로 남겨둘게요." : "알려준 여행 기간을 기억해둘게요.";
            case COMPANY -> "함께하는 사람도 기억해둘게요.";
            case MOOD -> "좋아요. 그런 여행을 생각하며 준비할게요.";
            case TRANSPORT -> {
                String mode = draft.value(DraftField.TRANSPORT);
                if ("car".equals(mode)) yield "차를 이용하는 여행으로 기억해둘게요.";
                if ("walk".equals(mode)) yield "걷고 싶은 마음을 일정에 참고할게요.";
                yield "이동 방법까지 정리했어요.";
            }
            case ANCHORS, EXTRAS -> "이제 나눈 이야기를 한 번 확인해볼까요?";
        };
    }

    private static Copy baseCopy(Slot slot, Draft draft, LocalDate today) {
        return switch (slot) {
            case CITY -> new Copy("어디로 떠나볼까요?",
                    "도시 이름만 적어도 좋아요. 떠올린 여행이 있다면 함께 들려주세요.", "가고 싶은 곳을 적어주세요",
                    List.of("부산", "제주에서 조용히 쉬고 싶어", "강릉으로 친구랑 여행 갈 거야"));
            case DATES -> {
                String city = draft.slotStatus(Slot.CITY) == FieldStatus.KNOWN ? draft.value(DraftField.DESTINATION) : null;
                String sample = sampleRange(today);
                yield new Copy(city != null ? city + "에는 언제 떠날 생각인가요?" : "언제 떠날 생각인가요?",
                        "정해둔 날짜나 여행 기간을 편하게 말해주세요.", "예: " + sample,
                        List.of(sample, "다음 달에 2박 3일", "날짜는 아직 몰라"));
            }
            case COMPANY -> new Copy("누구와 함께 가나요?", "함께하는 사람을 알려주세요.", "예: 연인과 함께",
                    List.of("연인과 함께", "친구 3명이랑, 나까지 4명", "부모님과 5살 아이, 총 4명"));
            case MOOD -> new Copy("이번 여행에서 뭘 하고 싶어요?",
                    "꼭 멋진 계획이 없어도 괜찮아요. 떠오르는 느낌이면 충분해요.", "예: 바다 보면서 느긋하게 쉬고 싶어",
                    List.of("낮에는 쉬고 저녁에는 야경 보고 싶어", "맛있는 거 먹고 골목 구경할래", "아직 잘 모르겠어"));
            case TRANSPORT -> new Copy("여행지에서는 어떻게 이동하나요?", "생각해둔 이동 방법을 짧게 알려주세요.",
                    "예: 차 가져갈 거야", List.of("자동차 이용할 거임", "주로 걸어 다닐래", "걸어 다니다 멀면 택시 탈래"));
            case ANCHORS -> anchorsCopy(draft.value(DraftField.DESTINATION));
            case EXTRAS -> new Copy("더 알려주고 싶은 게 있어요", "예산, 도착 시간, 피할 음식 등 무엇이든",
                    "예: 첫날 오후 3시에 도착해. 해산물은 못 먹어.", List.of());
        };
    }

    // 시안 예시는 부산 장소라 다른 여행지에서는 장소 이름 없는 예시로 바꾼다.
    private static Copy anchorsCopy(String destination) {
        boolean busan = destination == null || destination.contains("부산");
        return new Copy("이미 정해둔 게 있나요?", "숙소나 꼭 가고 싶은 곳, 예약한 일정이 있다면 알려주세요.",
                busan ? "예: 해운대에서 자고 싶고, 블루라인파크 가고 싶어" : "예: 숙소는 정했고 꼭 가고 싶은 곳이 있어",
                busan ? List.of("숙소는 아직 없고 광안리는 꼭 갈래", "21일 오후 2시에 블루라인파크 예약했어", "정해둔 건 없어")
                        : List.of("숙소는 아직 없어", "둘째 날 오후 2시에 체험 예약했어", "정해둔 건 없어"));
    }

    /** 시안 예시 "11월 20일부터 25일까지"처럼 열흘 뒤 5박. 지난 날짜 예시가 나오지 않게 오늘 기준으로 만든다. */
    static String sampleRange(LocalDate today) {
        LocalDate start = today.plusDays(10);
        LocalDate end = start.plusDays(5);
        String endText = end.getMonthValue() != start.getMonthValue()
                ? end.getMonthValue() + "월 " + end.getDayOfMonth() + "일" : end.getDayOfMonth() + "일";
        return start.getMonthValue() + "월 " + start.getDayOfMonth() + "일부터 " + endText + "까지";
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
