package com.shg.trip.shgtrip.global.exception;

import com.shg.trip.shgtrip.domain.auth.entity.OAuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 클라이언트가 고쳐야 하는 요청이 500으로 응답되지 않는지 본다. */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class SampleController {
        record Body(OAuthProvider provider) {}

        @PostMapping("/sample")
        String post(@RequestBody Body body) { return "ok"; }

        @GetMapping("/sample")
        String get(@RequestParam int page) { return "ok"; }

        @GetMapping("/business")
        String business() { throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 날짜의 마지막 일정은 삭제할 수 없습니다."); }

        @GetMapping("/ai")
        String ai() { throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "모델 응답 원문"); }

        @GetMapping("/deadlock")
        String deadlock() { throw new org.springframework.dao.CannotAcquireLockException("deadlock detected"); }

        @GetMapping("/conflict")
        String conflict() { throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Object.class, 1L); }

        @GetMapping("/cookie")
        String cookie(@org.springframework.web.bind.annotation.CookieValue("refresh_token") String token) { return "ok"; }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 없는_enum_값은_400() throws Exception {
        mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"FACEBOOK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 깨진_JSON과_빈_본문은_400() throws Exception {
        mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 파라미터_형식이_틀리거나_없으면_400() throws Exception {
        mockMvc.perform(get("/sample").param("page", "abc")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/sample")).andExpect(status().isBadRequest());
    }

    @Test
    void 필수_쿠키가_없으면_400() throws Exception {
        mockMvc.perform(get("/cookie")).andExpect(status().isBadRequest());
    }

    @Test
    void 지원하지_않는_메서드는_405() throws Exception {
        mockMvc.perform(delete("/sample"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.error.code").value("COMMON_003"));
    }

    @Test
    void 지원하지_않는_본문_형식은_415() throws Exception {
        mockMvc.perform(post("/sample").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void 업무_예외는_서버가_정한_문구를_그대로_보낸다() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("해당 날짜의 마지막 일정은 삭제할 수 없습니다."));
    }

    @Test
    void 외부_원인이_담기는_코드는_기본_문구로_숨긴다() throws Exception {
        mockMvc.perform(get("/ai"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.AI_SERVICE_ERROR.getMessage()));
    }

    @Test
    void 동시_수정_충돌은_409() throws Exception {
        mockMvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_003"));
        mockMvc.perform(get("/deadlock")).andExpect(status().isConflict());
    }
}
