# ☕ CLAUDE_BACKEND.md - Spring Boot 백엔드 설정

> Spring Boot + Java 백엔드 개발 시 적용되는 규칙

---

## 📌 기술 스택

```yaml
Language: Java 17+
Framework: Spring Boot 3.x
Build: Gradle (Kotlin DSL 권장) 또는 Maven
DB: MySQL 8.x (AWS RDS)
ORM: Spring Data JPA + QueryDSL
API: RESTful API (JSON)
Auth: JWT (Access + Refresh Token)
Docs: SpringDoc OpenAPI (Swagger)
Test: JUnit 5 + Mockito + TestContainers
```

---

## 🏗️ 프로젝트 구조

```
backend/
├── src/main/java/com/tripplan/
│   ├── TripPlanApplication.java
│   ├── global/                    # 공통 설정
│   │   ├── config/                # Configuration 클래스
│   │   ├── exception/             # 전역 예외 처리
│   │   ├── response/              # 공통 응답 포맷
│   │   ├── security/              # Security 설정
│   │   └── util/                  # 유틸리티
│   ├── domain/                    # 도메인별 패키지
│   │   ├── user/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── repository/
│   │   │   ├── entity/
│   │   │   ├── dto/
│   │   │   └── exception/
│   │   ├── trip/
│   │   ├── place/
│   │   └── schedule/
│   └── infra/                     # 외부 연동
│       ├── ai/                    # AI 서비스 연동
│       ├── storage/               # S3 등
│       └── payment/               # 결제 연동
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   ├── application-dev.yml
│   └── application-prod.yml
└── src/test/
```

---

## 🔐 보안 필수 규칙

### 1. SQL Injection 방지
```java
// ❌ 절대 금지 - 문자열 연결
String sql = "SELECT * FROM users WHERE id = " + userId;

// ✅ 필수 - 파라미터 바인딩
@Query("SELECT u FROM User u WHERE u.id = :userId")
Optional<User> findByUserId(@Param("userId") Long userId);

// ✅ JPA 메서드 쿼리 사용
Optional<User> findByEmail(String email);

// ✅ QueryDSL 사용
queryFactory
    .selectFrom(user)
    .where(user.id.eq(userId))
    .fetchOne();
```

### 2. XSS 방지
```java
// DTO에서 입력값 검증
public record TripCreateRequest(
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[가-힣a-zA-Z0-9\\s]+$", message = "특수문자 불가")
    String title,

    @Size(max = 1000)
    String description
) {}

// HTML Escape 처리 (필요시)
import org.springframework.web.util.HtmlUtils;
String safeContent = HtmlUtils.htmlEscape(userInput);
```

### 3. CSRF / CORS 설정
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())  // JWT 사용 시 disable
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // ... 생략
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:3000",
            "https://your-domain.com"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### 4. 인증/인가
```java
// JWT 토큰 검증 필터
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Authentication auth = jwtTokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
```

### 5. 민감 정보 관리
```yaml
# application.yml - 환경변수 참조
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}

jwt:
  secret: ${JWT_SECRET}
  access-expiration: 3600000      # 1시간
  refresh-expiration: 604800000   # 7일

ai:
  api-key: ${AI_API_KEY}
  base-url: ${AI_SERVICE_URL}
```

---

## 📝 코드 작성 규칙

### 1. Controller
```java
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
@Tag(name = "Trip", description = "여행 일정 API")
public class TripController {

    private final TripService tripService;

    @PostMapping
    @Operation(summary = "여행 일정 생성")
    public ResponseEntity<ApiResponse<TripResponse>> createTrip(
            @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody TripCreateRequest request) {
        
        TripResponse response = tripService.createTrip(user.getId(), request);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(response));
    }

    @GetMapping("/{tripId}")
    @Operation(summary = "여행 일정 조회")
    public ResponseEntity<ApiResponse<TripResponse>> getTrip(
            @PathVariable Long tripId,
            @AuthenticationPrincipal UserPrincipal user) {
        
        TripResponse response = tripService.getTrip(tripId, user.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
```

### 2. Service
```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;
    private final PlaceRepository placeRepository;
    private final AiServiceClient aiServiceClient;

    @Transactional
    public TripResponse createTrip(Long userId, TripCreateRequest request) {
        // 1. 비즈니스 로직 검증
        validateTripDates(request.startDate(), request.endDate());
        
        // 2. 엔티티 생성
        Trip trip = Trip.builder()
            .userId(userId)
            .title(request.title())
            .destination(request.destination())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .theme(request.theme())
            .build();
        
        // 3. 저장
        Trip savedTrip = tripRepository.save(trip);
        
        // 4. 응답 변환
        return TripResponse.from(savedTrip);
    }

    private void validateTripDates(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new InvalidTripDateException("시작일이 종료일보다 늦을 수 없습니다.");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new InvalidTripDateException("과거 날짜로 여행을 생성할 수 없습니다.");
        }
    }
}
```

### 3. Entity
```java
@Entity
@Table(name = "trips")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trip extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 100)
    private String destination;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripTheme theme;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status = TripStatus.DRAFT;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Schedule> schedules = new ArrayList<>();

    @Builder
    public Trip(Long userId, String title, String destination,
                LocalDate startDate, LocalDate endDate, TripTheme theme) {
        this.userId = userId;
        this.title = title;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.theme = theme;
    }

    // 비즈니스 메서드
    public void updateStatus(TripStatus status) {
        this.status = status;
    }

    public void addSchedule(Schedule schedule) {
        schedules.add(schedule);
        schedule.setTrip(this);
    }

    public int getTripDays() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }
}
```

### 4. Repository (QueryDSL 활용)
```java
public interface TripRepository extends JpaRepository<Trip, Long>, TripRepositoryCustom {
    
    List<Trip> findByUserIdOrderByStartDateDesc(Long userId);
    
    @Query("SELECT t FROM Trip t WHERE t.userId = :userId AND t.status = :status")
    List<Trip> findByUserIdAndStatus(@Param("userId") Long userId, 
                                      @Param("status") TripStatus status);
}

// Custom Repository
public interface TripRepositoryCustom {
    Page<Trip> searchTrips(TripSearchCondition condition, Pageable pageable);
}

@Repository
@RequiredArgsConstructor
public class TripRepositoryImpl implements TripRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Trip> searchTrips(TripSearchCondition condition, Pageable pageable) {
        List<Trip> content = queryFactory
            .selectFrom(trip)
            .where(
                userIdEq(condition.getUserId()),
                destinationContains(condition.getDestination()),
                themeEq(condition.getTheme()),
                statusEq(condition.getStatus())
            )
            .orderBy(trip.startDate.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory
            .select(trip.count())
            .from(trip)
            .where(
                userIdEq(condition.getUserId()),
                destinationContains(condition.getDestination()),
                themeEq(condition.getTheme()),
                statusEq(condition.getStatus())
            )
            .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    private BooleanExpression userIdEq(Long userId) {
        return userId != null ? trip.userId.eq(userId) : null;
    }

    private BooleanExpression destinationContains(String destination) {
        return StringUtils.hasText(destination) 
            ? trip.destination.contains(destination) : null;
    }
    // ... 생략
}
```

---

## 🛡️ 예외 처리

### 공통 응답 포맷
```java
@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final ErrorInfo error;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, null, new ErrorInfo(errorCode));
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(errorCode.getCode(), message));
    }
}

@Getter
@AllArgsConstructor
public class ErrorInfo {
    private final String code;
    private final String message;

    public ErrorInfo(ErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }
}
```

### 전역 예외 핸들러
```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
        
        log.warn("Validation failed: {}", message);
        return ResponseEntity
            .badRequest()
            .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

---

## 📊 성능 최적화

### 1. N+1 방지
```java
// ❌ N+1 발생
@Query("SELECT t FROM Trip t")
List<Trip> findAll();  // schedules 조회 시 N+1

// ✅ Fetch Join
@Query("SELECT t FROM Trip t JOIN FETCH t.schedules WHERE t.userId = :userId")
List<Trip> findByUserIdWithSchedules(@Param("userId") Long userId);

// ✅ EntityGraph
@EntityGraph(attributePaths = {"schedules", "schedules.place"})
@Query("SELECT t FROM Trip t WHERE t.id = :tripId")
Optional<Trip> findByIdWithSchedulesAndPlaces(@Param("tripId") Long tripId);
```

### 2. 페이지네이션
```java
// Pageable 사용
@GetMapping
public ResponseEntity<ApiResponse<Page<TripResponse>>> getTrips(
        @AuthenticationPrincipal UserPrincipal user,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "startDate") String sort,
        @RequestParam(defaultValue = "desc") String direction) {
    
    Sort.Direction sortDirection = Sort.Direction.fromString(direction);
    Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
    
    Page<TripResponse> response = tripService.getTrips(user.getId(), pageable);
    return ResponseEntity.ok(ApiResponse.success(response));
}
```

### 3. 캐싱 (선택)
```java
@EnableCaching
@Configuration
public class CacheConfig {
    // Redis 또는 Caffeine 캐시 설정
}

@Service
public class PlaceService {
    
    @Cacheable(value = "places", key = "#placeId")
    public PlaceResponse getPlace(Long placeId) {
        // DB 조회
    }

    @CacheEvict(value = "places", key = "#placeId")
    public void updatePlace(Long placeId, PlaceUpdateRequest request) {
        // 업데이트 후 캐시 삭제
    }
}
```

---

## 🧪 테스트 규칙

### 필수 테스트 대상
- Service 계층 단위 테스트
- Repository 계층 통합 테스트
- Controller 계층 MockMvc 테스트
- 중요 비즈니스 로직 (금액 계산, 날짜 검증 등)

### 테스트 예시
```java
@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @Mock
    private TripRepository tripRepository;

    @InjectMocks
    private TripService tripService;

    @Test
    @DisplayName("여행 생성 성공")
    void createTrip_Success() {
        // given
        Long userId = 1L;
        TripCreateRequest request = new TripCreateRequest(
            "제주도 여행",
            "제주도",
            LocalDate.now().plusDays(7),
            LocalDate.now().plusDays(10),
            TripTheme.HEALING
        );

        Trip savedTrip = Trip.builder()
            .userId(userId)
            .title(request.title())
            .destination(request.destination())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .theme(request.theme())
            .build();

        given(tripRepository.save(any(Trip.class))).willReturn(savedTrip);

        // when
        TripResponse response = tripService.createTrip(userId, request);

        // then
        assertThat(response.title()).isEqualTo("제주도 여행");
        assertThat(response.destination()).isEqualTo("제주도");
        verify(tripRepository).save(any(Trip.class));
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 예외 발생")
    void createTrip_InvalidDate_ThrowsException() {
        // given
        TripCreateRequest request = new TripCreateRequest(
            "잘못된 여행",
            "서울",
            LocalDate.now().plusDays(10),  // 시작일이 더 늦음
            LocalDate.now().plusDays(7),
            TripTheme.HEALING
        );

        // when & then
        assertThatThrownBy(() -> tripService.createTrip(1L, request))
            .isInstanceOf(InvalidTripDateException.class);
    }
}
```

---

## 📋 체크리스트

### API 개발 시
- [ ] 입력값 Validation (@Valid, @NotBlank 등)
- [ ] 적절한 HTTP 상태 코드 반환
- [ ] Swagger 문서화 (@Operation, @Tag)
- [ ] 예외 처리 및 에러 응답 통일
- [ ] 인증/인가 적용

### DB 작업 시
- [ ] EXPLAIN으로 쿼리 플랜 확인
- [ ] 인덱스 필요 여부 검토
- [ ] N+1 쿼리 체크
- [ ] 트랜잭션 범위 최소화
- [ ] 대량 데이터 시 페이지네이션

### 배포 전
- [ ] 환경변수 분리 확인
- [ ] 로그 레벨 적절히 설정
- [ ] 민감 정보 노출 없음 확인
- [ ] Health check 엔드포인트 존재

---

_Last Updated: 2025-02_
