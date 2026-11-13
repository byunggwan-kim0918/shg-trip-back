package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.shg.trip.shgtrip.domain.planning.service.ItineraryRulesTest.context;
import static com.shg.trip.shgtrip.domain.planning.service.ItineraryRulesTest.step;
import static org.assertj.core.api.Assertions.assertThat;

class ItineraryRulesPropertyTest {

    private static final String SEAFOOD = "Dining and Drinking > Restaurant > Seafood Restaurant";
    private static final String DESSERT = "Dining and Drinking > Dessert Shop";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String PENSION = "Travel and Transportation > Lodging > Vacation Rental";

    private final ItineraryRules rules = new ItineraryRules();

    /** 저장본·재실행·편집 후 스텝은 순서가 제각각으로 들어와도 같은 판정을 받아야 한다. */
    @Property(tries = 50)
    void verdictDoesNotDependOnInputOrder(@ForAll Random random) {
        List<StepData> steps = new ArrayList<>(List.of(
                step(1, 1, "08:46", "09:56", "브리앙", DESSERT, 13.8),
                step(2, 1, "11:36", "12:46", "조개구이", SEAFOOD, 19.6),
                step(3, 1, "13:08", "13:58", "카페", CAFE, 18.3),
                step(4, 1, "17:30", "18:40", "조개찜", SEAFOOD, 14.7),
                step(5, 1, "18:52", "19:22", "펜션", PENSION, 9.8),
                step(6, 2, "09:00", "10:00", "해변", BEACH, 5.0),
                step(7, 2, "11:30", "12:40", "횟집", SEAFOOD, 40.0)));
        List<String> expected = messages(steps);

        Collections.shuffle(steps, random);

        assertThat(messages(steps)).containsExactlyInAnyOrderElementsOf(expected);
    }

    private List<String> messages(List<StepData> steps) {
        return rules.evaluate(steps, List.of(), context("relaxed", 2)).violations().stream()
                .map(ItineraryRules.Violation::message).toList();
    }
}
