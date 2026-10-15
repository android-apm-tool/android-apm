# 설계 문서

기준일: 2026-10-15. 제품 요구사항, 기술 설계, 구현 결과를 구분해 저장한다.

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [01. 제품 요구사항](01-product-requirements.md) | 용어, 지원 범위, 확정된 화면, 수집 지표 |
| [02. 시스템 구조](02-system-architecture.md) | Gradle, 컴파일러 플러그인, Android 에이전트, 로컬 서버 |
| [03. 화면 발견과 Nav3 연결](03-nav3-entry-discovery.md) | 전체 Composable 수집, UI 진입점, provider 연결, 모듈 병합, 빌드 캐시 |
| [04. 화면 전환 분석](04-navigation-transition-analysis.md) | 탐색 상태 변경, 콜백 추적, 실행 중 전환 확인 |
| [05. 데이터 모델](05-data-model.md) | 카탈로그, 전환, 트레이스, 측정값, 식별자 |
| [06. 검증 계획](06-validation-plan.md) | 구현 순서, 테스트 시나리오, 미결정 사항 |
| [07. 플러그인 개발](07-plugin-development.md) | 초기 모듈, 고정 버전, 로컬 샘플 연결, 빌드·검증 명령 |
| [08. 카탈로그](08-catalog-v1.md) | 수집·병합 작업, 읽기용 JSON v2, 캐시, 미해결 범위 |
| [09. 카탈로그 구현 결정과 검증](09-catalog-decisions-and-validation.md) | 수집 파이프라인, 내부 v1/보고서 v2 분리, 샘플·룰업 결과, 후속 범위 |
| [10. SootUp/Heros 분석기](10-sootup-heros-analysis.md) | 누락 반례와 설계 오류, 대화에서 선택한 이유, 엔진·IFDS·source bridge, 구현/후속 파이프라인 |
| [11. UI 진단·분류·디자인 대응](11-ui-diagnosis-and-design-matching.md) | 미연결 원인, Preview·문맥별 역할, 조건 근거, 디자인 후보와 수동 근거 분리, 반례·캐시 검증 |
| [12. AI 없는 전환·접근 경로 상세 설계](12-transition-and-access-path-design.md) | K2 IR·SootUp/Heros 역할, 추가 수집, 객체·DI·이벤트·상태 연결, 조건부 경로 탐색, 선택적 바이너리 분석, 반례·인수 기준 |
| [13. 로컬 분석과 외부 전송 차단](13-local-analysis-security.md) | AI 미사용, 고객 통제 경계, 도구 사전 준비, 격리 runner, Gradle·캐시·CI·로그·viewer·디바이스의 전송 차단, 실패 정책·비전송 시험 |
| [14. 전환·격리 구현과 검증](14-offline-transitions-implementation.md) | 구현 책임과 선택 이유, 실제 파이프라인, 실행 명령, 반례·샘플·룰업 검증, 미지원 capability |
| [15. 접근 경로 발견 범위 개선](15-access-path-coverage.md) | 룰업 46/48(95.83%) 검증, 상속·callback·조건 식별 손실, 탐색 예산, 오연결 반례 |

## 결정 상태

| 구분 | 내용 |
| --- | --- |
| 확정된 제품 방향 | 프로젝트 플러그인 + localhost, 화면 중심 탐색, 디바이스별 트레이스 |
| 2026-10-15 분석·보안 방향 | 제품 분석은 AI 없이 오픈소스 엔진과 명시적 규칙으로 수행. 코드·결과·로그는 고객 통제 경계 내부에 보관. 외부 전송 없는 자체 모델만 향후 별도 검토 가능하며 현재 도입하지 않음 |
| 확정된 첫 분석 방향 | Compose + Nav3, 전체 Composable 수집 + UI 진입점 추적 + 키·콘텐츠 연결 |
| 기술 설계안 | 모듈별 카탈로그, content·provider·DI 연결 해석, 정적 전환 분석 + 실행 관측 |
| 초기 구현·로컬 검증 완료 | Gradle 플러그인 적용, K2 서비스·IR 연결, AGP 9 내장 Kotlin·Compose 샘플 컴파일. 버전·범위는 [개발 문서](07-plugin-development.md) 참조 |
| 검증 목표 | SDUI를 제외한 지원 범위의 화면 정의 발견률 99% 이상. 현재 미측정 |
| 카탈로그 수집·병합 구현 | 별도 전체 컴파일, Nav3 content 연결, 모듈 병합, 캐시·삭제·실패 처리 |
| 읽기용 JSON v2 구현 | 2칸 들여쓰기, 중복 참조 정리, 내부 분석 데이터 분리. 파일 경로 유지·스키마 버전 변경 |
| 2026-10-14 분석 엔진 도입 | SootUp 3.0.1/Heros 1.2.3 실행, Lazy·반복 슬롯 규칙, 전체 선언 공개 보존. 범위·검증은 10번 문서 참조 |
| 소비 프로젝트 검증 | 룰업 37개 모듈, 등록 경로 후보 48개, 보고서 134,222,027 → 343,633 bytes. 화면 발견률은 미측정 |
| 2026-10-14 UI 진단·디자인 대응 | 미연결 원인, 문맥별 부분 역할 분류, 조건 근거, 자동 후보와 수동 source 재검증. 범위·검증은 11번 문서 참조 |
| 2026-10-15 전환·격리 구현 | AI 없이 문맥·객체·Channel 전달과 launcher 경로 분석, Linux/WSL2 namespace·seccomp runner, 실제 Gradle 전송 반례. 검증 결과·한계는 14번 참조 |
| 2026-10-15 경로 발견 범위 개선 | 고정 48개 중 launcher 44개(91.67%), 입증된 warm 외부 입력 포함 46개(95.83%). 기존 누락 23개 중 21개 복구, 기존 launcher 25개 회귀 0. 정적·조건부 경로이며 전체 모델은 TRUNCATED. 15번 참조 |
| 구현 전 검증 필요 | 추가 버전·variant 호환성, DI 자동 연결, 독립 화면·상태의 완전한 분류, 코루틴 인과관계 계측 |
| 아직 미결정 | 초기 검증 조합 외 지원 버전, 화면 상태 판별 규칙, 자원 수집 API, 저장소·전송 프로토콜 |

기술 설계안은 구현 결과나 성능 보장을 뜻하지 않는다. 개발 중 검증된 결정은 이 표와 해당 문서에 함께 반영한다.

## 읽을 때의 기준

- `선언 발견`, `provider 연결`, `실제 등록`, `실제 표시`는 서로 다른 사실이다.
- Composable 선언, 화면 후보, 화면 정의, 화면 상태·컴포넌트를 구분한다. 모든 Composable을 화면으로 세지 않는다.
- UI 구성 관계는 전환 경로가 아니다. 동작·상태 변경·표시 대상의 연결을 따로 해석한다.
- 미해결 항목을 삭제하거나 측정하지 않은 값을 0으로 표시하지 않는다.
- 카탈로그는 앱과 variant별로 관리한다.
- 화면 목록 수집, 화면 전환 관계 발견, 화면 상태 발견의 분석 범위를 각각 기록한다.
- AI 미사용과 외부 전송 차단은 별도로 검증한다. 별도 JVM·localhost·Gradle offline만으로 고객 코드 비전송을 보장하지 않는다.
- 99%는 정답 목록이 있는 평가 집합에서 검증할 화면 정의 발견률 목표다. 연결 해석률·경로 발견률·정밀도도 별도 보고하며, 모든 Android 프로젝트에 대한 보장으로 사용하지 않는다.
- 원래 목표인 모든 화면·경로·자원의 자동 분석은 지원 범위를 넓혀 가며 달성할 제품 목표다. 첫 Nav3 어댑터의 완전성 보장과 동일하지 않다.

## 디자인 참조

- [유저 로드 지도](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-61)
- [인바운드 경로 및 디바이스별 트레이스](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-62)
- [함수 호출 및 자원 시계열](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=101-11996)
- [구성 탐색·빌드 의존성](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=161-5059)
