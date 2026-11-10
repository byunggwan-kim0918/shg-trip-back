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

/** trip_chat_sessions.state JSONB. 구조를 바꾸면 SCHEMA_VERSION을 올린다(이전 세션은 이어하지 않고 새로 시작). */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatState {

    public static final int SCHEMA_VERSION = 1;

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
