package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import java.util.List;

record GapFill(List<StepData> steps, PlaceCandidate prev, int minutes) {}
