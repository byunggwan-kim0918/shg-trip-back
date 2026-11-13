# SSE 일정 생성 스트리밍의 다중 인스턴스 대응

> 백엔드를 다중 인스턴스로 수평 확장할 때 일정 생성 진행률 SSE가 정상 동작하기 위한 전제와,
> 이를 위해 적용한 코드 변경을 정리한다.

## 배경: 왜 문제가 되는가

일정 생성 흐름은 **두 개의 별개 HTTP 요청**으로 이루어진다.

1. `POST /api/itineraries/generate` — 작업 시작. `TravelPlannerService`가 `GenerationStream`을 만들어
   **그 요청을 받은 인스턴스의 인메모리 맵(`jobs`)**에 등록하고, `@Async` executor를 백그라운드로 돌린다.
2. `GET /api/itineraries/generate/{jobId}/stream` — SSE 연결. `openStream`이 로컬 `jobs` 맵의 `GenerationStream`에
   새 `SseEmitter`를 붙이고 지난 이벤트를 다시 보낸다.

`SseEmitter`는 특정 프로세스의 살아있는 소켓 객체라 **직렬화·인스턴스 이동이 불가능**하다.
따라서 다중 인스턴스에서 두 요청이 서로 다른 인스턴스로 라우팅되면 GET 쪽 인스턴스에는 작업이 없어
메모리 기록만으로는 진행률을 보낼 수 없다. 앱이 1대여도 배포 중에는 잠깐 2대가 떠서 같은 일이 생긴다.

## 해법 1 (코드): 진행 이벤트를 Redis에도 남겨 다른 인스턴스가 이어 보낸다

emitter는 옮길 수 없지만 **이벤트 내용은 옮길 수 있다.** 작업을 돌리는 인스턴스가 보내는 SSE 프레임을 Redis에도
남기고, 작업이 없는 인스턴스로 온 GET은 그 기록을 처음부터 이어 보낸다.

| 키 | 내용 |
|---|---|
| `gen:owner:{jobId}` | 작업을 시작한 userId (TTL 12분). 다른 인스턴스가 소유자를 확인한다 |
| `gen:events:{jobId}` | SSE 프레임 목록(`event:…\ndata:…\n\n` 그대로, TTL 12분). 빈 문자열은 작업 끝 표시 |

- `GenerationStream`이 이벤트를 보낼 때마다 `GenerationEventLog`가 같은 프레임을 Redis 목록에 붙인다.
  작업이 끝나면(정상·오류·취소) 끝 표시를 붙인다. Redis 오류는 로그만 남기고 생성은 멈추지 않는다.
- `TravelPlannerService.openStream` 순서
  1. 이 인스턴스 메모리에 작업이 있으면 지금처럼 메모리 기록을 다시 보내고 이어 붙인다
  2. 완료 결과(`gen:result:{jobId}`)가 있으면 `complete` 하나를 보내고 닫는다
  3. `gen:owner:{jobId}`가 요청자면 `GenerationStreamRelay`가 Redis 기록을 0.5초 주기로 읽어 새 프레임만 보낸다.
     끝 표시를 만나면 닫는다
  4. 그 밖에는 `error` 이벤트 하나로 거절한다
- 중계는 연결마다 블로킹 읽기를 잡지 않는다. 공용 Redis 연결이 막히지 않게 짧은 주기로 `LRANGE`만 한다.
- 작업을 돌리던 인스턴스가 죽으면 끝 표시가 남지 않는다. 중계 연결은 SSE 타임아웃(10분)에 닫히고, 프론트는 결과 재조회로 넘어간다.

### ALB sticky session은 쓰지 않는다

요청 경로가 브라우저 → 웹(Next.js BFF) → private ALB → 앱이라, ALB 쿠키(`AWSALB`)를 다시 보내는 클라이언트는 BFF 서버다.
BFF 프록시는 세션·refresh 쿠키만 주고받아 sticky를 켜도 같은 인스턴스로 고정되지 않는다. BFF가 jobId별로 ALB 쿠키를
보관하게 만들면 BFF가 ALB 동작에 묶인다. 그래서 타깃 그룹(`terraform/alb.tf`의 `aws_lb_target_group.app`)과 리스너의
stickiness는 꺼 둔다(`enabled = false`).

ALB idle timeout은 60초다. 생성 중에는 30초마다 heartbeat를 보내(`OptimizedGenerationExecutor.executeWithHeartbeat`)
연결이 끊기지 않는다.

## 해법 2 (코드): 작업 상태를 Redis로 공유

이벤트 중계와 별개로 **"유저당 동시 생성 1개"** 불변식도 크로스-인스턴스가 필요하다.
배포·스케일 중 라우팅이 바뀌어 유저가 다른 인스턴스로 붙어 새 생성을 시작하면, 기존 인스턴스에서
도는 executor를 취소해야 하는데 취소 플래그가 인메모리면 인스턴스를 넘지 못한다(좀비 executor가
LLM 비용을 계속 쓴다).

그래서 다음 두 상태를 Redis로 이관했다. emitter를 담는 `jobs` 맵은 인메모리로 유지한다(위 소켓 종속성 때문).

| 상태 | 이전 | 이후 |
|---|---|---|
| 유저의 활성 jobId | `TravelPlannerService.activeUserJobs`(ConcurrentHashMap) | Redis 키 `active:user:{userId}` (TTL 12분) |
| 취소 플래그 | `CancellationRegistry.cancelledJobs`(ConcurrentHashMap) | Redis 키 `cancel:job:{jobId}` (TTL 12분) + 로컬 캐시 |

### CancellationRegistry — Redis + 로컬 캐시 하이브리드

- `cancel(jobId)` → `SET cancel:job:{jobId} = "1"` (TTL 12분) + 로컬 캐시 표시
- `isCancelled(jobId)` → **로컬 캐시 우선**(취소 표시 있으면 즉시 true), 미스 시에만 Redis `hasKey`
- **진실의 원천은 Redis 키**(at-least-once). 로컬 캐시는 정상 경로에서 매 폴링마다 Redis를 때리지
  않기 위한 최적화일 뿐이다. executor는 파이프라인 각 단계 사이에서 `isCancelled(jobId)`를 폴링하므로,
  다른 인스턴스의 취소도 다음 체크포인트에서 Redis 키를 읽어 감지한다.
- executor·`StoryGenerationService`의 호출부는 **시그니처가 그대로라 변경 없음.**

### activeUserJobs — CAS(compare-and-set/delete) 시맨틱 보존

`startGeneration`은 `getAndSet(active:user:{userId}, newJobId)`로 활성 jobId를 원자적으로 교체하고,
직전 값(기존 job)이 있으면 그 job만 취소한다.

정리(취소/완료/eviction) 시에는 **"현재 값이 이 jobId일 때만 삭제"**를 Lua 스크립트로 수행한다.
기존 인메모리 코드의 `activeUserJobs.remove(userId, jobId)`(값 비교 삭제)와 동일한 시맨틱으로,
`A→B` 재생성 레이스에서 A의 취소가 B가 방금 등록한 새 jobId를 지우지 않도록 방어한다.

```lua
-- COMPARE_AND_DELETE
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
```

## 끊긴 연결 복구 UX

인스턴스 종료·스케일·롤링 배포 시 그 인스턴스에 붙어 있던 SSE 연결은 끊긴다. 소켓이 프로세스에 묶여 있어서다.
다시 연결하면 다른 인스턴스가 Redis 기록을 이어 보내고, 결과 자체도 유실되지 않는다:

- 구조 저장 완료 시 `GenerationResultStore`가 `gen:result:{jobId} = itineraryId`를 Redis에 TTL 10분으로 남긴다.
- 프론트는 SSE가 끊기면 `GET /api/itineraries/generate/{jobId}/result`로 재조회해 완료된 itineraryId를 회복한다.

즉 "실시간 진행률"은 Redis 이벤트 기록으로, "최종 결과"는 Redis 결과 조회로 지킨다.

## 관련 코드

| 파일 | 역할 |
|---|---|
| `domain/planning/service/TravelPlannerService.java` | jobId/emitter 생명주기, `active:user:{userId}` Redis 관리, CAS 정리 |
| `domain/planning/service/CancellationRegistry.java` | 취소 플래그 Redis + 로컬 캐시 |
| `domain/planning/service/GenerationResultStore.java` | 완료 결과(itineraryId) Redis 저장 — 결과 복구 경로 |
| `domain/planning/service/GenerationEventLog.java` | 진행 이벤트 프레임·작업 소유자 Redis 기록 |
| `domain/planning/service/GenerationStreamRelay.java` | 다른 인스턴스 작업의 Redis 기록을 SSE로 이어 보냄 |
| `domain/planning/controller/PlanningController.java` | generate/stream/result 엔드포인트 |

---
_관련 문서: [`CLAUDE.md`](../../CLAUDE.md), [`AWS_INFRASTRUCTURE.md`](../../AWS_INFRASTRUCTURE.md)_
