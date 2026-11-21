package com.shg.trip.shgtrip.domain.tripchat.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** trip_chat_sessions.state JSONB. 구조를 바꾸면 SCHEMA_VERSION을 올리고, 옮길 수 있는 이전 버전은 migrate에서 옮긴다. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatState {

    public static final int SCHEMA_VERSION = 2;

    private int schemaVersion = SCHEMA_VERSION;
    private long seq;
    private List<DraftEvent> events = new ArrayList<>();
    private List<Turn> turns = new ArrayList<>();
    private OpenQuestion openQuestion;
    private PendingTurn pending;
    private Set<String> askedKeys = new LinkedHashSet<>();
    private int haikuCalls;
    /** "LODGING|이름" 같은 키로 이름에 맞춘 DB 장소. 여행지·정해둔 것이 바뀔 때 다시 맞춘다. */
    private Map<String, PlaceMatch> matches = new LinkedHashMap<>();
    private String matchSignature;
    /** 수정을 취소하면 돌아갈 질문 */
    private OpenQuestion resume;
    private Long confirmedVersion;

    /** 읽은 이전 버전 상태를 지금 버전으로 옮긴다. 옮길 수 없는 버전이면 false다. */
    public boolean migrate() {
        if (schemaVersion == 1) {
            events = prioritiesAsList(events);
            schemaVersion = SCHEMA_VERSION;
        }
        return schemaVersion == SCHEMA_VERSION;
    }

    /** v1의 PRIORITIES는 값 하나였다. 접기 규칙(추론은 여행자 값을 덮지 못함)으로 살아남는 SET만 목록 항목으로 남긴다. */
    private static List<DraftEvent> prioritiesAsList(List<DraftEvent> v1) {
        DraftEvent kept = null;
        for (DraftEvent e : v1) {
            if (e.field() != DraftField.PRIORITIES) continue;
            if (e.op() == EventOp.CLEAR) {
                kept = null;
            } else if (e.op() == EventOp.SET
                    && (kept == null || !kept.source().fromUser() || e.source().fromUser())) {
                kept = e;
            }
        }
        List<DraftEvent> out = new ArrayList<>();
        for (DraftEvent e : v1) {
            if (e.field() != DraftField.PRIORITIES || e.op() != EventOp.SET) {
                out.add(e);
            } else if (e == kept) {
                out.add(new DraftEvent(e.seq(), e.slot(), e.field(), EventOp.ADD, e.value(), e.status(), e.source(),
                        e.raw(), e.date(), e.time()));
            }
        }
        return out;
    }

    public long nextSeq() {
        return ++seq;
    }

    public boolean hasTurn(String clientTurnId) {
        return turns.stream().anyMatch(t -> t.clientTurnId().equals(clientTurnId));
    }

    @JsonIgnore
    public int questionCount() {
        return askedKeys.size();
    }
}
