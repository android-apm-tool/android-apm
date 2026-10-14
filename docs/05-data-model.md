# 데이터 모델

전체 제품의 논리 모델 제안이다. 정적 수집기는 내부 JSON v1과 읽기용 JSON v2를 구현했다. 실행 데이터의 저장 엔진·직렬화 형식·API 경로는 미결정이다.

## 현재 직렬화 계약

2026-10-14부터 병합 결과에 `ui-analysis.json`과 별도 `ui-evidence.json`을 추가한다. 선택적 디자인 매칭 결과는 `design-matches.json`이다. 각 파일은 v1 독립 스키마이며 현재 공개 카탈로그 v2를 대체하지 않는다. 실제 필드·근거·미확정 상태는 [UI 분석 계약](11-ui-diagnosis-and-design-matching.md)에 정리한다.

| 구분 | 용도 | 스키마 |
| --- | --- | --- |
| 내부 카탈로그 v1 | 모듈 병합에 필요한 함수·값·호출·조건·선언·연결 사실 | [catalog-v1.schema.json](../schema/catalog-v1.schema.json) |
| 공개 보고서 v2 | 경로 후보, UI·Nav3 연결, 참조 Composable, 소스·분석 상태·진단 | [catalog-report-v2.schema.json](../schema/catalog-report-v2.schema.json) |

v2의 `composableIds`는 같은 문서의 `composables[].id`를 참조한다. 여러 호출 위치에서 같은 선언을 사용해도 정의는 한 번 기록하며, 오버로드 ID는 합치지 않는다. 호출별 전체 표현식·인자·조건은 내부 v1에 남긴다. 공개 보고서는 다시 병합할 수 있는 원본 모델이 아니다.

`summary.collectedComposables`는 수집 선언 수, `referencedComposables`는 보고서의 UI 연결이 참조한 함수 수다. 외부 함수도 참조 목록에 포함하므로 두 수의 크기는 단순 비교할 수 없고 화면 개수로 사용하지 않는다. `screenCandidates`는 현재 `UNCLASSIFIED`이며 화면 정의와 동일하지 않다.

v2는 출력 파일 경로를 유지하면서 스키마 버전을 변경한다. 이전 필드 대응과 실제 출력 항목은 [카탈로그 사용법](08-catalog-v1.md)을 따른다. 아래 전체 논리 모델의 모든 항목을 현재 JSON에서 제공하는 것은 아니다.

## 식별 범위

| 식별자 | 범위와 용도 |
| --- | --- |
| `projectId` | 분석 대상 프로젝트 |
| `appId`, `variantId` | 대상 앱과 빌드 구성 |
| `catalogId` | 입력 지문과 분석기 버전으로 식별한 카탈로그 |
| `buildId` | 실제 계측 앱 빌드 |
| `moduleId` | included build 등까지 구분하는 모듈 식별자 |
| `composableDeclarationId` | Composable 함수·람다 선언. 화면 ID와 구분 |
| `uiRootId` | Compose UI 진입점의 정적 선언 |
| `uiRegionDefinitionId`, `uiRegionInstanceId` | 콘텐츠를 선택·표시하는 영역의 정적 정의와 실행 인스턴스 |
| `contentBindingId` | 콘텐츠가 호출·슬롯·분기·provider에 연결되는 사용 맥락 |
| `screenCandidateId`, `screenDefinitionId` | 화면 후보와 분류 근거가 있는 화면 정의 |
| `declarationId` | 특정 카탈로그의 entry 등록 지점 |
| `providerId` | 정적 provider 정의 지점 |
| `bindingId` | provider와 등록 선언의 연결 |
| `hostDefinitionId` | 정적으로 식별한 NavDisplay 지점 |
| `sessionId`, `deviceId` | 측정 세션과 기기 |
| `hostInstanceId`, `entryInstanceId` | 실행 중 Nav3 host와 entry 인스턴스 |
| `screenInstanceId` | Nav3 밖 화면도 포함하는 실행 인스턴스. entryInstanceId는 해당할 때 연결 |
| `causeId` | 행동부터 비동기 작업·전환까지 연결하는 인과 식별자 |
| `invocationId` | 함수 호출 한 번의 식별자 |
| `clockDomainId` | 시각이 속한 시계 기준 |

소스 식별자는 모듈, 상대 파일 경로, enclosing symbol, 호출 지점 등을 조합한다. 줄 번호만 사용하지 않는다. 빌드 간 코드 이동이나 리팩터링에도 동일 ID가 유지된다고 보장하지 않는다.

실행 이벤트는 `buildId`와 해당 빌드의 카탈로그에 연결한다. 동일 화면 타입의 여러 인스턴스를 하나로 합치지 않는다.

## 정적 카탈로그

| 모델 | 주요 필드 |
| --- | --- |
| `ComposableDeclaration` | composableDeclarationId, symbol·contentRef, moduleId, sourceRef, sourceSet, 어노테이션 |
| `UiRootDefinition` | uiRootId, 진입점 종류·심볼, contentRef, Activity·View 참조, resolution |
| `UiRegionDefinition` | uiRegionDefinitionId, 루트·상위 영역 참조, 콘텐츠 선택 상태·분기, sourceRef |
| `ContentRelation` | 호출·슬롯 전달·조건 분기·host 포함·키 연결 종류, 양끝 참조, 조건, sourceRef, resolution |
| `ContentBinding` | contentBindingId, 콘텐츠 참조, 호출 맥락, 루트·provider·host 후보, 타입·인자 치환, 근거 경로 |
| `TypeRelation`, `DiBinding` | 타입 구현·override, 객체 전달, 바인딩·기여 함수, qualifier·component·scope, 근거 |
| `EventFlowRelation` | 채널·스트림 참조, 생산·소비 지점, 메시지 타입·인자, 변환·필터 조건, resolution |
| `ScreenCandidate` | screenCandidateId, contentBindingRefs, 제안 역할, 분류 근거·규칙 버전, 미해결 사유 |
| `ScreenDefinition` | screenDefinitionId, Nav3 목적지·독립 콘텐츠 종류, contentBindingRefs, entryBindingRefs, 경계 판정 근거·자동/수동 출처 |
| `ProviderDefinition` | providerId, moduleId, sourceRef, 생성 함수·receiver 참조 |
| `EntryDeclaration` | declarationId, API 종류, 키 타입·표현식, contentRef, conditionRefs, sourceRef |
| `RegistrationFunction` | 함수 심볼, receiver·파라미터, 내부 선언·호출 요약 |
| `EntryBinding` | bindingId, providerId, declarationId, 등록 함수 호출 경로, 타입·인자 치환 |
| `NavHostDefinition` | hostDefinitionId, provider 참조, 탐색 상태 참조, sourceRef |
| `TransitionCandidate` | 출발·목적 화면·콘텐츠 후보, 관련 entry binding, triggerRef, 연산, 조건, 인자 전달, host·표시 영역, 근거 경로, resolution |
| `ScreenStateDefinition` | screenRef, 상태 이름·판별 규칙·출처 |
| `ProjectConfiguration` | variant, 모듈·의존성·권한·RES·테스트 구성 |
| `AnalysisDiagnostic` | 코드, 대상 참조, 원인, 지원 범위·분석 한계 |

`ScreenCandidate`의 역할은 화면·상태·컴포넌트·미분류로 구분한다. 하나의 Composable 선언은 사용 맥락에 따라 여러 content binding과 역할을 가질 수 있다. 화면 정의와 entry binding도 같은 함수명이나 키 타입만으로 병합하지 않는다. 후보가 화면으로 분류되면 원래 후보와 정의 사이의 대응을 보존한다.

`ContentRelation`은 UI 구성 관계이고 `TransitionCandidate`는 동작에 따른 이동·변경 후보다. 두 모델의 간선을 서로 대체하지 않는다. runtime 전환 경로와도 별도로 관리한다.

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
| 역할 분류 | 화면 / 상태 / 컴포넌트 / 미분류, 사용 맥락과 근거 |
| 연결 해석 | 연결 완료 / 후보 집합 / 미해결 |
| 루트 관계 | 연결된 루트 후보 / 연결 미해결. 실행 도달성 판정과 구분 |
| 실행 등록 | 특정 세션에서 실제 등록을 관측했는지 |
| 화면 표시 | 특정 화면·entry 인스턴스를 표시했는지 |
| 상태 도달 | 어떤 판별 규칙으로 상태 도달을 확인했는지 |
| 분석 범위 | 지원 패턴, 처리한 모듈, 미분석 의존성, 제한 도달 여부 |

미관측은 접근 불가를 뜻하지 않는다. 정적 후보가 있다고 실제로 실행 가능한 경로라고 확정하지 않는다.

## 전환 관측

```text
TransitionObservation
  observationId
  sessionId / deviceId / buildId
  hostInstanceId? / uiRegionInstanceId?
  causeId? / triggerRef?
  originScreenInstanceId? / originEntryInstanceId?
  stackBefore? / stackAfter?
  uiSelectionBefore? / uiSelectionAfter?
  visibleScreensBefore / visibleScreensAfter
  visibleEntriesBefore? / visibleEntriesAfter?
  operation
  requestedAt? / committedAt? / displayedAt?
  clockDomainId
  candidateId?
  resolution / lossInfo
```

`originScreenInstanceId`는 행동이 발생한 화면이고 Nav3 entry라면 `originEntryInstanceId`도 연결한다. 전환 직전 표시 집합과 달라질 수 있다. 외부 진입에는 앱 화면 출발점이 없을 수 있다. Nav3 밖 상태 전환에는 stack이나 entry를 만들어 채우지 않는다. 관측하지 못한 선택 상태와 해당하지 않는 필드는 구분한다.

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

평가 결과는 사용자 프로젝트 카탈로그와 별도 `DiscoveryEvaluation`으로 보관한다. 평가 집합·정답 목록 버전, 프로젝트 commit·variant, 지원 버전·패턴, 분석기·규칙 버전, SDUI 등 사전 제외 범위, 자동/수동 실행 조건, 화면 TP·FN·FP, 연결 성공/대상 수, 경로 TP·FN·FP, 미분류·미해결·분석 제한 건수를 포함한다. [검증 계획](06-validation-plan.md)의 산식으로 지표를 계산하고 프로젝트별·패턴별·전체 결과를 함께 보존한다.

## 버전 관리

- 산출물과 이벤트에 스키마 버전을 포함한다.
- 지원하지 않는 버전은 명시적으로 거부하거나 부분 읽기 정책을 적용한다.
- unknown 값을 보존할 수 있는 호환 정책을 정한다.
- 카탈로그·실행 데이터·측정 조건을 함께 보관해 잘못된 빌드 간 결합을 방지한다.
