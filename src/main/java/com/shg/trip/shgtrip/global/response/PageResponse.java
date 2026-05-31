package com.shg.trip.shgtrip.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 페이지네이션 응답 래퍼.
 * Spring Data 내부 구현체(PageImpl) 노출 방지.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
