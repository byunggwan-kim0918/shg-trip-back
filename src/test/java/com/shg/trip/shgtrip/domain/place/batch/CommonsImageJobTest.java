package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommonsImageJobTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("SPARQL 대표 사진 주소에서 파일 이름을 디코딩해 항목별로 꺼낸다")
    void parsesImageFiles() throws Exception {
        String json = """
                {"results":{"bindings":[
                  {"item":{"value":"http://www.wikidata.org/entity/Q1"},
                   "img":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Senso-ji%20%E6%B5%85%E8%8D%89.jpg"}},
                  {"item":{"value":"http://www.wikidata.org/entity/Q2"},"img":{"value":"not a file path"}}
                ]}}""";

        assertThat(CommonsImageJob.parseImageFiles(mapper.readTree(json)))
                .containsExactly(Map.entry("Q1", "Senso-ji 浅草.jpg"));
    }

    @Test
    @DisplayName("파일 이름의 +는 공백으로 바꾸지 않는다")
    void keepsPlusInFileName() throws Exception {
        String json = """
                {"results":{"bindings":[{"item":{"value":"http://www.wikidata.org/entity/Q3"},
                 "img":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/A+B%20C.jpg"}}]}}""";

        assertThat(CommonsImageJob.parseImageFiles(mapper.readTree(json))).containsEntry("Q3", "A+B C.jpg");
    }

    @Test
    @DisplayName("정규화된 제목으로 되짚어 썸네일·평문 저작자·라이선스를 꺼내고, 라이선스 없는 파일은 뺀다")
    void parsesImageInfo() throws Exception {
        String json = """
                {"query":{
                  "normalized":[{"from":"File:a_b.jpg","to":"File:A b.jpg"}],
                  "pages":[
                    {"title":"File:A b.jpg","imageinfo":[{
                      "thumburl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/A_b.jpg/960px-A_b.jpg",
                      "descriptionurl":"https://commons.wikimedia.org/wiki/File:A_b.jpg",
                      "extmetadata":{
                        "Artist":{"value":"<a href=\\"//commons.wikimedia.org/wiki/User:Kim\\">Kim &amp; Lee</a>"},
                        "LicenseShortName":{"value":"CC BY-SA 4.0"}}}]},
                    {"title":"File:No license.jpg","imageinfo":[{
                      "thumburl":"https://upload.wikimedia.org/x.jpg","extmetadata":{}}]}
                  ]}}""";

        Map<String, CommonsImageJob.CommonsImage> images = CommonsImageJob.parseImageInfo(mapper.readTree(json),
                List.of("a_b.jpg", "No license.jpg", "Missing.jpg"));

        assertThat(images).containsOnlyKeys("a_b.jpg");
        CommonsImageJob.CommonsImage image = images.get("a_b.jpg");
        assertThat(image.thumbUrl()).endsWith("/960px-A_b.jpg");
        assertThat(image.credit()).isEqualTo("Kim & Lee");
        assertThat(image.license()).isEqualTo("CC BY-SA 4.0");
        assertThat(image.pageUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:A_b.jpg");
    }

    @Test
    @DisplayName("태그만 있는 저작자는 null이고 긴 저작자는 자른다")
    void plainTextTrims() {
        assertThat(CommonsImageJob.plainText("<span> </span>", 200)).isNull();
        assertThat(CommonsImageJob.plainText("x".repeat(300), 200)).hasSize(200);
    }
}
