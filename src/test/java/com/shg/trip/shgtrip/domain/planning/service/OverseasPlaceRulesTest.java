package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 해외(일본·중국·대만·베트남) 이름에 대한 장소 판정 규칙. 한국 규칙과 겹치는 낱말의 오탐을 함께 고정한다. */
class OverseasPlaceRulesTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String DESSERT = "Dining and Drinking > Dessert Shop";
    private static final String ATTRACTION = "Landmarks and Outdoors > Tourist Attraction";

    @Test
    @DisplayName("교통시설 이름: 공항·역·버스터미널·부두를 잡고, 道の駅·駅前·加油站·Gà는 잡지 않는다")
    void transitNameSignal() {
        for (String hub : List.of("成田国際空港", "東京駅", "上海虹桥火车站", "上海虹桥站", "北京南站 (北京南站)",
                "高鐵台中站", "浦东国际机场", "桃園國際機場",
                "捷運台北車站", "台北轉運站 客運站", "Sân bay Tân Sơn Nhất", "Ga Hà Nội", "Bến xe Miền Đông",
                "서울역", "Narita International Airport")) {
            assertThat(PlaceCategoryConstants.hasTransitNameSignal(hub)).as(hub).isTrue();
        }
        for (String notHub : List.of("道の駅 富士吉田", "駅前ラーメン", "中国石化加油站", "特斯拉充电站", "菜鸟驿站",
                "站前小吃", "Cơm Gà Bà Buội",
                "Gà Rán Popeyes", "역사박물관")) {
            assertThat(PlaceCategoryConstants.hasTransitNameSignal(notHub)).as(notHub).isFalse();
        }
    }

    @Test
    @DisplayName("공항 부속시설과 여객시설은 현지어로도 알아보고, 관광 부두는 여객시설로 보지 않는다")
    void hubSubFacilityAndPassengerFacility() {
        assertThat(PlaceCategoryConstants.isHubSubFacility("成田空港 第1ターミナル 駐車場")).isTrue();
        assertThat(PlaceCategoryConstants.isHubSubFacility("浦东机场 停车场")).isTrue();
        assertThat(PlaceCategoryConstants.isHubSubFacility("Narita International Airport")).isFalse();
        assertThat(PlaceCategoryConstants.isPassengerFacility("新宿バスターミナル")).isTrue();
        assertThat(PlaceCategoryConstants.isPassengerFacility("淡水漁人碼頭")).isFalse();
    }

    @Test
    @DisplayName("현지 편의점은 카페 자리가 아니고, 이름에 全家가 든 디저트 가게는 카페 자리다")
    void convenienceStores() {
        assertThat(PlaceCategoryConstants.isCafeLike("全家便利商店 信義店", DESSERT, null)).isFalse();
        assertThat(PlaceCategoryConstants.isCafeLike("7-ELEVEN 台北101門市", DESSERT, null)).isFalse();
        assertThat(PlaceCategoryConstants.isCafeLike("セブン-イレブン 新宿店", DESSERT, null)).isFalse();
        assertThat(PlaceCategoryConstants.isCafeLike("全家福甜品", DESSERT, null)).isTrue();
    }

    @Test
    @DisplayName("미식 거리는 집합 POI이고 야시장은 아니다. 보행자 거리는 경유형이 아니다")
    void aggregateAndViaRoute() {
        assertThat(PlaceCategoryConstants.isAggregatePoi("南京路美食街", RESTAURANT)).isTrue();
        assertThat(PlaceCategoryConstants.isAggregatePoi("Phố ẩm thực Tô Hiến Thành", RESTAURANT)).isTrue();
        assertThat(PlaceCategoryConstants.isAggregatePoi("士林夜市", RESTAURANT)).isFalse();
        assertThat(PlaceCategoryConstants.isViaRoute("Nanjing Road Pedestrian Street", ATTRACTION, List.of())).isFalse();
        assertThat(PlaceCategoryConstants.isViaRoute("南京东路步行街", ATTRACTION, List.of("드라이브"))).isFalse();
        assertThat(PlaceCategoryConstants.isViaRoute("Coastal Road", ATTRACTION, List.of())).isTrue();
    }

    @Test
    @DisplayName("세부 유형: 식당은 음식 유형만, 명소는 지형·시설 유형만 본다")
    void subTypeScopedByMajorCategory() {
        assertThat(PlaceCategoryConstants.subType("城下町", RESTAURANT, null, null)).isEqualTo("restaurant");
        assertThat(PlaceCategoryConstants.subType("海底捞火锅", "Dining and Drinking > Restaurant > Chinese Restaurant",
                null, null)).isEqualTo("soup");
        assertThat(PlaceCategoryConstants.subType("林東芳牛肉麵", "Dining and Drinking > Restaurant > Taiwanese Restaurant",
                null, null)).isEqualTo("noodle");
        assertThat(PlaceCategoryConstants.subType("Phở Thìn", "Dining and Drinking > Restaurant > Vietnamese Restaurant",
                null, null)).isEqualTo("noodle");
        assertThat(PlaceCategoryConstants.subType("面包工坊", RESTAURANT, null, null)).isEqualTo("bakery");
        assertThat(PlaceCategoryConstants.subType("明治神宮", ATTRACTION, null, null)).isEqualTo("temple");
        assertThat(PlaceCategoryConstants.subType("롯데면세점", ATTRACTION, null, null)).isEqualTo("shopping");
        assertThat(PlaceCategoryConstants.subType("Bảo tàng Hồ Chí Minh", ATTRACTION, null, null)).isEqualTo("museum");
    }

    @Test
    @DisplayName("주소 규칙은 한국 행에만 건다. 지정 구역 이름 규칙은 해외에도 건다")
    void visitableEntity() {
        assertThat(PlaceCategoryConstants.isVisitableEntity("九份老街", "", "TW")).isTrue();
        assertThat(PlaceCategoryConstants.isVisitableEntity("某관광특구", "台北市", "TW")).isFalse();
        assertThat(PlaceCategoryConstants.isVisitableEntity("어떤곳", "", "KR")).isFalse();
        assertThat(PlaceCategoryConstants.isVisitableEntity("어떤곳", "서울특별시", null)).isFalse();
        assertThat(PlaceCategoryConstants.isVisitableEntity("어떤곳", "서울특별시 종로구 사직로 161", "KR")).isTrue();
    }

    @Test
    @DisplayName("지점명: 괄호 속 店과 공백 뒤 …店을 떼고, 공백 없이 붙은 이름은 그대로 둔다")
    void branchSuffix() {
        assertThat(AlternativeBuilder.stripBranchSuffix("星巴克(人民广场店)")).isEqualTo("星巴克");
        assertThat(AlternativeBuilder.stripBranchSuffix("鼎泰豐（信義店）")).isEqualTo("鼎泰豐");
        assertThat(AlternativeBuilder.stripBranchSuffix("一蘭 渋谷店")).isEqualTo("一蘭");
        assertThat(AlternativeBuilder.stripBranchSuffix("一蘭渋谷店")).isEqualTo("一蘭渋谷店");
        assertThat(AlternativeBuilder.stripBranchSuffix("흑본오겹 함덕점")).isEqualTo("흑본오겹");
    }

    @Test
    @DisplayName("빈 시간 보충에서 거르는 약한 분류는 리프 정확 일치로만 본다")
    void weakFillLeaves() {
        assertThat(PlaceCategoryConstants.isWeakFill("Arts and Entertainment > Roller Rink")).isTrue();
        assertThat(PlaceCategoryConstants.isWeakFill("Arts and Entertainment > Arcade")).isTrue();
        assertThat(PlaceCategoryConstants.isWeakFill("Business and Professional Services > Convention Center")).isTrue();
        assertThat(PlaceCategoryConstants.isWeakFill("Landmarks and Outdoors > Structure")).isTrue();
        assertThat(PlaceCategoryConstants.isWeakFill("Retail > Shopping Arcade")).isFalse();
        assertThat(PlaceCategoryConstants.isWeakFill("Arts and Entertainment > Museum")).isFalse();
    }
}
