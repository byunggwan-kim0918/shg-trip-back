# SSE 일정 생성 스트리밍의 다중 인스턴스 대응

> 백엔드를 다중 인스턴스로 수평 확장할 때 일정 생성 진행률 SSE가 정상 동작하기 위한 전제와,
> 이를 위해 적용한 코드 변경을 정리한다.

## 배경: 왜 문제가 되는가

일정 생성 흐름은 **두 개의 별개 HTTP 요청**으로 이루어진다.

1. `POST /api/itineraries/generate` — 작업 시작. `TravelPlannerService`가 `SseEmitter`를 만들어
   **그 요청을 받은 인스턴스의 인메모리 맵(`jobs`)**에 등록하고, `@Async` executor를 백그라운드로 돌린다.
2. `GET /api/itineraries/generate/{jobId}/stream` — SSE 연결. `getEmitter(jobId)`가 로컬 `jobs` 맵에서
   emitter를 찾아 반환한다. 없으면 `RESOURCE_NOT_FOUND`.

`SseEmitter`는 특정 프로세스의 살아있는 소켓 객체라 **직렬화·인스턴스 이동이 불가능**하다.
따라서 다중 인스턴스에서 두 요청이 서로 다른 인스턴스로 라우팅되면 GET 쪽 인스턴스에는 emitter가 없어
SSE 연결 자체가 실패한다.

## 해법 1 (인프라): ALB sticky session

**같은 유저의 요청을 같은 인스턴스로 고정**하면 POST가 만든 emitter가 있는 인스턴스로 GET도 도달한다.
이것이 SSE/WebSocket 다중 인스턴스의 실무 표준이며, Redis Pub/Sub 같은 이벤트 브로커보다 단순하고
동시성 리스크(emitter 동시 send)가 없다. Pub/Sub은 "인스턴스가 죽으면 그 소켓도 죽는다"는 근본
한계를 어차피 해결하지 못하므로, 이 프로젝트 규모에서는 sticky session이 비용 대비 최적이다.

### 적용 방법 (AWS 콘솔)

> ⚠️ 이 프로젝트의 target group은 Terraform에서 `resource`가 아니라 `data "aws_lb_target_group"`으로
> **참조만** 한다(`terraform/ecs.tf`, 콘솔에서 `target_type = ip`로 수동 생성). 따라서 stickiness는
> tf 코드가 아니라 콘솔 attribute로 켠다.

1. EC2 콘솔 → Target groups → **`shgtrip-app-tg`** 선택
2. Attributes 탭 → Edit
3. **Stickiness: Enabled**, type = **`Load balancer generated cookie`(lb_cookie)**, duration ≈ **600초**
   (SSE 타임아웃 10분과 정합. Application-based cookie가 아니라 LB 쿠키를 쓴다 — 앱이 쿠키를 발급하지 않아도 됨.)
4. 저장

> ⚠️ **ALB idle timeout도 함께 확인**: ALB의 idle timeout 기본값은 60초라 SSE(최대 10분)보다 짧다.
> 앱이 30초 주기 heartbeat(`OptimizedGenerationExecutor.executeWithHeartbeat`)를 보내 연결을 유지하지만,
> 안전하게 ALB idle timeout을 SSE 타임아웃(600초) 이상으로 올려두면 프록시가 중간에 끊는 일이 없다.

향후 target group을 Terraform `resource "aws_lb_target_group"`으로 이관한다면 아래 블록으로 코드화한다.

```hcl
resource "aws_lb_target_group" "app" {
  # ... name, port, target_type = "ip" 등 ...
  stickiness {
    enabled         = true
    type            = "lb_cookie"
    cookie_duration = 600
  }
}
```

## 해법 2 (코드): 작업 상태를 Redis로 공유

sticky session을 쓰더라도 **"유저당 동시 생성 1개"** 불변식은 크로스-인스턴스가 필요하다.
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

인스턴스 종료·스케일·롤링 배포 시 진행 중이던 SSE 연결은 끊긴다(sticky든 Pub/Sub이든 공통 한계 —
소켓이 프로세스에 묶여 있어 인스턴스가 죽으면 소켓도 죽는다). 이때 결과 자체는 유실되지 않는다:

- 구조 저장 완료 시 `GenerationResultStore`가 `gen:result:{jobId} = itineraryId`를 Redis에 TTL 10분으로 남긴다.
- 프론트는 SSE가 끊기면 `GET /api/itineraries/generate/{jobId}/result`로 재조회해 완료된 itineraryId를 회복한다.

즉 "실시간 진행률"은 sticky로 지키고, "최종 결과"는 Redis 조회로 지킨다.

## 관련 코드

| 파일 | 역할 |
|---|---|
| `domain/planning/service/TravelPlannerService.java` | jobId/emitter 생명주기, `active:user:{userId}` Redis 관리, CAS 정리 |
| `domain/planning/service/CancellationRegistry.java` | 취소 플래그 Redis + 로컬 캐시 |
| `domain/planning/service/GenerationResultStore.java` | 완료 결과(itineraryId) Redis 저장 — 결과 복구 경로 |
| `domain/planning/controller/PlanningController.java` | generate/stream/result 엔드포인트 |

---
_관련 문서: [`CLAUDE.md`](../../CLAUDE.md), [`AWS_INFRASTRUCTURE.md`](../../AWS_INFRASTRUCTURE.md)_
