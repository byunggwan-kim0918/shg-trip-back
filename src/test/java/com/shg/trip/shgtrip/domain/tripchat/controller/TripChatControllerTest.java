package com.shg.trip.shgtrip.domain.tripchat.controller;

import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatAnswerRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.service.TripChatService;
import com.shg.trip.shgtrip.global.exception.GlobalExceptionHandler;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripChatControllerTest {

    private final TripChatService service = mock(TripChatService.class);
    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TripChatController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(7L, "USER"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TripChatResponse sample() {
        return new TripChatResponse(id.toString(), 3, "ASKING", null, null, List.of(),
                new TripChatResponse.Summary(List.of(), List.of(), false), null, null, null, null);
    }

    @Test
    @DisplayName("진행 중 대화가 없으면 data가 null이다")
    void currentNone() throws Exception {
        mvc.perform(get("/api/trip-chats/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("답을 서비스로 넘긴다")
    void answer() throws Exception {
        when(service.answer(eq(7L), eq(id), any(TripChatAnswerRequest.class))).thenReturn(sample());

        mvc.perform(post("/api/trip-chats/{id}/answers", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"q\",\"clientTurnId\":\"t\",\"kind\":\"text\",\"text\":\"부산\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    @DisplayName("허용하지 않은 답 종류는 400")
    void rejectsUnknownKind() throws Exception {
        mvc.perform(post("/api/trip-chats/{id}/answers", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"q\",\"clientTurnId\":\"t\",\"kind\":\"html\",\"text\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("만들기·수정·수정 취소·확인·보관 요청을 서비스로 넘긴다")
    void otherEndpoints() throws Exception {
        when(service.create(7L, "req")).thenReturn(sample());
        when(service.edit(7L, id, 3L, Slot.DATES)).thenReturn(sample());
        when(service.cancelEdit(7L, id, 3L)).thenReturn(sample());
        when(service.confirm(7L, id, 3L)).thenReturn(sample());

        mvc.perform(post("/api/trip-chats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"req\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/trip-chats/{id}/edits", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3,\"slot\":\"DATES\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/trip-chats/{id}/edits/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}")).andExpect(status().isOk());
        mvc.perform(post("/api/trip-chats/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}")).andExpect(status().isOk());
        mvc.perform(delete("/api/trip-chats/current")).andExpect(status().isOk());
        mvc.perform(delete("/api/trip-chats/{id}", id)).andExpect(status().isOk());

        verify(service).archiveCurrent(7L);
        verify(service).archive(7L, id);
    }

    @Test
    @DisplayName("인증 정보가 없으면 AUTH_003")
    void unauthenticated() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/trip-chats/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_003"));
    }
}
