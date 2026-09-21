package com.shg.trip.shgtrip.domain.planning.service;

import java.math.BigDecimal;

record TransportLeg(String mode, int durationMin, BigDecimal distanceKm, BigDecimal cost) {}
