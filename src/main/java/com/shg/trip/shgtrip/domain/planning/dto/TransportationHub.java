package com.shg.trip.shgtrip.domain.planning.dto;

public record TransportationHub(
    String arrivalHub,   // nullable
    String departureHub, // nullable
    String hubType       // AIRPORT | BUS_TERMINAL | TRAIN_STATION | FERRY | null
) {}
