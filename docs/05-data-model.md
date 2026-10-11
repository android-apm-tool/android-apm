# 데이터 모델

논리 모델의 제안이다. 저장 엔진, 직렬화 형식, API 경로는 아직 확정하지 않았다.

## 식별 범위

| 식별자 | 범위와 용도 |
| --- | --- |
| `projectId` | 분석 대상 프로젝트 |
| `appId`, `variantId` | 대상 앱과 빌드 구성 |
| `catalogId` | 입력 지문과 분석기 버전으로 식별한 카탈로그 |
| `buildId` | 실제 계측 앱 빌드 |
| `moduleId` | included build 등까지 구분하는 모듈 식별자 |
| `declarationId` | 특정 카탈로그의 entry 등록 지점 |
| `providerId` | 정적 provider 정의 지점 |
| `bindingId` | provider와 등록 선언의 연결 |
| `hostDefinitionId` | 정적으로 식별한 NavDisplay 지점 |
| `sessionId`, `deviceId` | 측정 세션과 기기 |
| `hostInstanceId`, `entryInstanceId` | 실행 중 host와 화면 인스턴스 |
| `causeId` | 행동부터 비동기 작업·전환까지 연결하는 인과 식별자 |
| `invocationId` | 함수 호출 한 번의 식별자 |
| `clockDomainId` | 시각이 속한 시계 기준 |

소스 식별자는 모듈, 상대 파일 경로, enclosing symbol, 호출 지점 등을 조합한다. 줄 번호만 사용하지 않는다. 빌드 간 코드 이동이나 리팩터링에도 동일 ID가 유지된다고 보장하지 않는다.

실행 이벤트는 `buildId`와 해당 빌드의 카탈로그에 연결한다. 동일 화면 타입의 여러 인스턴스를 하나로 합치지 않는다.

## 정적 카탈로그

| 모델 | 주요 필드 |
| --- | --- |
| `ProviderDefinition` | providerId, moduleId, sourceRef, 생성 함수·receiver 참조 |
| `EntryDeclaration` | declarationId, API 종류, 키 타입·표현식, contentRef, conditionRefs, sourceRef |
| `RegistrationFunction` | 함수 심볼, receiver·파라미터, 내부 선언·호출 요약 |
| `EntryBinding` | bindingId, providerId, declarationId, 등록 함수 호출 경로, 타입·인자 치환 |
| `NavHostDefinition` | hostDefinitionId, provider 참조, 탐색 상태 참조, sourceRef |
| `TransitionCandidate` | 출발·목적 binding 후보, triggerRef, 연산, 조건, 인자 전달, host, resolution |
| `ScreenStateDefinition` | screenRef, 상태 이름·판별 규칙·출처 |
| `ProjectConfiguration` | variant, 모듈·의존성·권한·RES·테스트 구성 |
| `AnalysisDiagnostic` | 코드, 대상 참조, 원인, 지원 범위·분석 한계 |

키 표현식은 다음과 같이 구분하는 방식을 제안한다.

```text
KnownConstant(value)
ObjectReference(symbol)
ConstructorExpression(type, argumentExpressions)
ParameterReference(symbol)
DynamicExpression(sourceRef, staticType)
```

`ConstructorExpression`은 생성자를 실행했다는 뜻이 아니다. 식별 가능한 구조만 기록한다.

## 발견과 관측 상태

하나의 enum으로 모든 사실을 덮어쓰지 않고 독립적으로 기록한다.

| 차원 | 예시 |
| --- | --- |
| 선언 발견 | 선언 지점이 분석 입력에서 발견됨 |
| 연결 해석 | 연결 완료 / 후보 집합 / 미해결 |
| 실행 등록 | 특정 세션에서 실제 등록을 관측했는지 |
| 화면 표시 | 특정 entry 인스턴스를 표시했는지 |
| 상태 도달 | 어떤 판별 규칙으로 상태 도달을 확인했는지 |
| 분석 범위 | 지원 패턴, 처리한 모듈, 미분석 의존성, 제한 도달 여부 |

미관측은 접근 불가를 뜻하지 않는다. 정적 후보가 있다고 실제로 실행 가능한 경로라고 확정하지 않는다.

## 전환 관측

```text
TransitionObservation
  observationId
  sessionId / deviceId / buildId
  hostInstanceId
  causeId? / triggerRef?
  originEntryInstanceId?
  stackBefore / stackAfter
  visibleEntriesBefore / visibleEntriesAfter
  operation
  requestedAt? / committedAt? / displayedAt?
  clockDomainId
  candidateId?
  resolution / lossInfo
```

`originEntryInstanceId`는 행동이 발생한 화면이다. 전환 직전 표시 집합과 달라질 수 있다. 외부 진입에는 앱 화면 출발점이 없을 수 있다.

스택은 복구·초기 상태를 포함한 탐색 상태다. 스택 요소 간 인접성을 실제 전환 이력으로 변환하지 않는다.

## 트레이스와 호출

```text
Trace
  traceId
  selectedLoadId
  targetScreenRef / targetStateRef
  inboundRouteRef
  sessionId / deviceId / buildId
  startTime / endTime? / clockDomainId
  measurementConditions
  completeness

FunctionInvocation
  invocationId / functionRef
  parentInvocationId?
  logicalTaskId? / causeId?
  startTime / endTime?
  executionSegments[]
  completion: returned | threw | cancelled | incomplete
```

중단 가능한 함수의 전체 경과 시간에는 대기가 포함될 수 있다. 실제 실행 구간은 스레드·CPU 관측 정보와 연결해 별도로 보존한다. 비동기 작업의 부모 관계는 호출 스택의 동기 부모 관계와 구분한다.

호출 횟수는 저장된 호출 인스턴스에서 집계한다. 유실이 있으면 집계의 불완전성을 표시한다. 재구성이나 상태 머신의 재개를 사용자 코드의 새 호출로 잘못 세지 않도록 계측 의미를 정의해야 한다.

## 자원·외부 연결·UI 이벤트

| 모델 | 주요 필드 |
| --- | --- |
| `ResourceSample` | metricId, timestamp, clockDomainId, value, unit, scope, collector, quality |
| `NetworkRequest` | requestId, causeId/spanRef, 목적지, 시작·첫 바이트·종료, 응답·오류, collector |
| `CacheEvent` | cacheId, 계층, hit/miss, 시각, causeId/spanRef, 수집 방법 |
| `UiMilestone` | 화면·컴포넌트 참조, 단계, 시각, 판정 규칙, frame 참조 |
| `DeviceProfile` | 모델, OS, 사양, 수집 가능한 성능값, 단위·수집 방법 |

리소스 scope는 최소한 device/process/thread/직접 계측 구간을 구분한다. CPU 사용률의 정규화 방식과 메모리 지표 종류를 metric 정의에 포함한다.

함수 행을 선택하면 `[startTime, endTime]`과 겹치는 자원 샘플 구간을 강조한다. 이 상관관계를 함수의 독점적 자원 소비량으로 표시하지 않는다.

캐시 히트율은 관측된 히트 수를 히트·미스 합으로 나누는 등 분모가 명확한 규칙을 사용한다. 관측이 없거나 지원되지 않으면 0%로 표시하지 않는다.

## 프로젝트 구성과 커버리지

모듈 의존성은 내부 모듈 노드·간선과 외부 패키지 목록을 별도 보관한다. 권한은 merged manifest와 선언 출처를, RES는 종류·이름·구성 qualifier·소속 모듈을 연결하는 방향으로 설계한다. 테스트는 source set, runner, 의존성, 발견된 테스트 구성을 구분한다.

카탈로그마다 처리한 입력 모듈·파일 범위와 미지원 패턴을 기록한다. 전체 모수를 모르는 상황에서 임의의 발견률 백분율을 제공하지 않는다.

## 버전 관리

- 산출물과 이벤트에 스키마 버전을 포함한다.
- 지원하지 않는 버전은 명시적으로 거부하거나 부분 읽기 정책을 적용한다.
- unknown 값을 보존할 수 있는 호환 정책을 정한다.
- 카탈로그·실행 데이터·측정 조건을 함께 보관해 잘못된 빌드 간 결합을 방지한다.
