package com.shg.trip.shgtrip.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/** Spring Data 내부 구현체(PageImpl)를 응답에 노출하지 않으려는 래퍼. */
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
