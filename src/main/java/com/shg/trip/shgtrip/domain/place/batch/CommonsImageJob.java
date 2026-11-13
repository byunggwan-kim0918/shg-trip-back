package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 사진이 없는 장소에 Wikidata 대표 사진(P18)을 위키미디어 공용 썸네일 주소로 연결한다. 파일은 받지 않고 주소만 둔다.
 * 공용 파일은 저작자·라이선스 표시가 사용 조건이라 둘 다 없는 파일은 쓰지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
public class CommonsImageJob {

    private static final String COMMONS_API = "https://commons.wikimedia.org/w/api.php";
    private static final String FILE_PATH_MARKER = "Special:FilePath/";
    private static final String THUMB_HOST = "https://upload.wikimedia.org/";
    /** 형식이 다른 id가 VALUES에 하나라도 섞이면 묶음 전체가 쿼리 오류가 된다. */
    private static final Pattern QID = Pattern.compile("Q\\d+");

    /** SPARQL VALUES 한 번에 넣는 항목 수. */
    private static final int ITEM_BATCH_SIZE = 200;
    /** 공용 API가 한 요청에 받는 파일 제목 상한. */
    private static final int FILE_BATCH_SIZE = 50;
    /** 위키미디어 표준 썸네일 폭. 표준 폭이 아니면 매번 새로 렌더링해 느리다. */
    static final int THUMB_WIDTH = 960;
    private static final int MAX_CREDIT_LENGTH = 200;
    private static final int MAX_LICENSE_LENGTH = 100;
    /** 실패한 요청 묶음이 이 비율을 넘으면 단계 실패로 본다. */
    private static final double MAX_FAILED_RATIO = 0.2;

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CommonsImageJob(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** 공용 파일 하나의 표시 정보. */
    record CommonsImage(String thumbUrl, String credit, String license, String pageUrl) {}

    public void run() {
        List<PlaceRepository.ImageTarget> targets = placeRepository.findCommonsImageTargets().stream()
                .filter(t -> t.getWikidataId() != null && QID.matcher(t.getWikidataId()).matches())
                .toList();
        Map<String, List<Long>> idsByItem = targets.stream().collect(Collectors.groupingBy(
                PlaceRepository.ImageTarget::getWikidataId, LinkedHashMap::new,
                Collectors.mapping(PlaceRepository.ImageTarget::getId, Collectors.toList())));
        log.info("공용 사진 대상: 장소 {}곳, 항목 {}개", targets.size(), idsByItem.size());
        if (idsByItem.isEmpty()) return;

        int requests = 0;
        int failed = 0;
        Map<String, String> fileByItem = new LinkedHashMap<>();
        List<String> items = new ArrayList<>(idsByItem.keySet());
        for (int i = 0; i < items.size(); i += ITEM_BATCH_SIZE) {
            requests++;
            try {
                fileByItem.putAll(imageFiles(items.subList(i, Math.min(items.size(), i + ITEM_BATCH_SIZE))));
            } catch (Exception e) {
                failed++;
                log.warn("대표 사진 조회 실패 ({}~): {}", i, e.getMessage());
            }
        }

        Map<String, CommonsImage> imageByFile = new HashMap<>();
        List<String> files = fileByItem.values().stream().distinct().toList();
        for (int i = 0; i < files.size(); i += FILE_BATCH_SIZE) {
            requests++;
            try {
                imageByFile.putAll(imageInfo(files.subList(i, Math.min(files.size(), i + FILE_BATCH_SIZE))));
            } catch (Exception e) {
                failed++;
                log.warn("공용 파일 정보 조회 실패 ({}~): {}", i, e.getMessage());
            }
        }

        int saved = 0;
        int unusable = 0;
        for (Map.Entry<String, String> entry : fileByItem.entrySet()) {
            CommonsImage image = imageByFile.get(entry.getValue());
            if (image == null) {
                unusable++;
                continue;
            }
            for (Long id : idsByItem.get(entry.getKey())) {
                saved += placeRepository.updateAttributedImage(id, image.thumbUrl(), image.credit(), image.license(),
                        image.pageUrl());
            }
        }
        log.info("공용 사진 연결: 저장 {}곳, 대표 사진 있는 항목 {}개, 파일 정보·라이선스 없어 제외 {}개, 실패 요청 {}/{}",
                saved, fileByItem.size(), unusable, failed, requests);
        if (failed > requests * MAX_FAILED_RATIO) {
            throw new BatchStageFailed("공용 사진 요청 실패 " + failed + "/" + requests);
        }
    }

    /** 항목 → 대표 사진 파일 이름. 대표 사진이 여럿이면 이름순 첫 파일이라 실행마다 같다. */
    private Map<String, String> imageFiles(List<String> items) throws Exception {
        String values = items.stream().map(q -> "wd:" + q).collect(Collectors.joining(" "));
        String query = "SELECT ?item (MIN(STR(?image)) AS ?img) WHERE { VALUES ?item { " + values + " } "
                + "?item wdt:P18 ?image . } GROUP BY ?item";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WikidataSitelinksJob.SPARQL_ENDPOINT))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", WikidataSitelinksJob.USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        return parseImageFiles(objectMapper.readTree(WikidataSitelinksJob.sendWithRetry(httpClient, request, objectMapper)));
    }

    static Map<String, String> parseImageFiles(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode binding : root.path("results").path("bindings")) {
            String item = binding.path("item").path("value").asText("");
            String image = binding.path("img").path("value").asText("");
            int at = image.indexOf(FILE_PATH_MARKER);
            if (item.isEmpty() || at < 0) continue;
            String qid = item.substring(item.lastIndexOf('/') + 1);
            // URLDecoder는 폼 규칙이라 파일 이름의 "+"를 공백으로 바꾼다.
            String encoded = image.substring(at + FILE_PATH_MARKER.length()).replace("+", "%2B");
            out.put(qid, URLDecoder.decode(encoded, StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 파일 이름 → 썸네일·저작자·라이선스. 라이선스나 썸네일이 없는 파일은 빠진다. */
    private Map<String, CommonsImage> imageInfo(List<String> files) throws Exception {
        String titles = files.stream().map(f -> "File:" + f).collect(Collectors.joining("|"));
        String body = "action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url%7Cextmetadata"
                + "&iiurlwidth=" + THUMB_WIDTH + "&iiextmetadatafilter=Artist%7CLicenseShortName"
                + "&titles=" + URLEncoder.encode(titles, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COMMONS_API))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", WikidataSitelinksJob.USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return parseImageInfo(objectMapper.readTree(WikidataSitelinksJob.sendWithRetry(httpClient, request, objectMapper)),
                files);
    }

    /** API가 제목을 정규화(밑줄 → 공백, 첫 글자 대문자)해 돌려줘 요청한 이름으로 되짚는다. */
    static Map<String, CommonsImage> parseImageInfo(JsonNode root, List<String> files) {
        Map<String, String> normalized = new HashMap<>();
        for (JsonNode n : root.path("query").path("normalized")) {
            normalized.put(n.path("from").asText(), n.path("to").asText());
        }
        Map<String, JsonNode> pageByTitle = new HashMap<>();
        for (JsonNode page : root.path("query").path("pages")) {
            pageByTitle.put(page.path("title").asText(), page);
        }
        Map<String, CommonsImage> out = new HashMap<>();
        for (String file : files) {
            String title = "File:" + file;
            JsonNode page = pageByTitle.get(normalized.getOrDefault(title, title));
            CommonsImage image = page == null ? null : toImage(page.path("imageinfo").path(0));
            if (image != null) out.put(file, image);
        }
        return out;
    }

    private static CommonsImage toImage(JsonNode info) {
        String thumb = info.path("thumburl").asText(info.path("url").asText(""));
        String license = plainText(info.path("extmetadata").path("LicenseShortName").path("value").asText(""),
                MAX_LICENSE_LENGTH);
        if (!thumb.startsWith(THUMB_HOST) || license == null) return null;
        String credit = plainText(info.path("extmetadata").path("Artist").path("value").asText(""), MAX_CREDIT_LENGTH);
        return new CommonsImage(thumb, credit, license, info.path("descriptionurl").asText(null));
    }

    /** 공용 메타데이터는 링크·태그가 섞인 HTML이라 평문으로 바꾼다. 비면 null. */
    static String plainText(String html, int maxLength) {
        String text = HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
