package com.shg.trip.shgtrip.domain.itinerary.dto;

import java.time.OffsetDateTime;

public record ShareLinkResponse(
        String shareToken,
        OffsetDateTime expiresAt
) {}
