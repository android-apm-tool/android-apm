# 설계 문서

기준일: 2026-10-12. 지금까지 논의한 제품 요구사항과 기술 설계를 저장한다.

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
| [08. 카탈로그 v1](08-catalog-v1.md) | 구현된 수집·병합 작업, 고정 JSON 스키마, 캐시, 미해결 범위 |

## 결정 상태

| 구분 | 내용 |
| --- | --- |
| 확정된 제품 방향 | 프로젝트 플러그인 + localhost, 화면 중심 탐색, 디바이스별 트레이스 |
| 확정된 첫 분석 방향 | Compose + Nav3, 전체 Composable 수집 + UI 진입점 추적 + 키·콘텐츠 연결 |
| 기술 설계안 | 모듈별 카탈로그, content·provider·DI 연결 해석, 정적 전환 분석 + 실행 관측 |
| 초기 구현·로컬 검증 완료 | Gradle 플러그인 적용, K2 서비스·IR 연결, AGP 9 내장 Kotlin·Compose 샘플 컴파일. 버전·범위는 [개발 문서](07-plugin-development.md) 참조 |
| 검증 목표 | SDUI를 제외한 지원 범위의 화면 정의 발견률 99% 이상. 현재 미측정 |
| 카탈로그 v1 구현 | 별도 전체 컴파일, Nav3 content 연결, JSON 생성·병합, 캐시·삭제·실패 처리. 범위는 [v1 문서](08-catalog-v1.md) 참조 |
| 구현 전 검증 필요 | 추가 버전·variant 호환성, DI 자동 연결, 화면 역할 분류, 코루틴 인과관계 계측 |
| 아직 미결정 | 초기 검증 조합 외 지원 버전, 화면 상태 판별 규칙, 자원 수집 API, 저장소·전송 프로토콜 |

기술 설계안은 구현 결과나 성능 보장을 뜻하지 않는다. 개발 중 검증된 결정은 이 표와 해당 문서에 함께 반영한다.

## 읽을 때의 기준

- `선언 발견`, `provider 연결`, `실제 등록`, `실제 표시`는 서로 다른 사실이다.
- Composable 선언, 화면 후보, 화면 정의, 화면 상태·컴포넌트를 구분한다. 모든 Composable을 화면으로 세지 않는다.
- UI 구성 관계는 전환 경로가 아니다. 동작·상태 변경·표시 대상의 연결을 따로 해석한다.
- 미해결 항목을 삭제하거나 측정하지 않은 값을 0으로 표시하지 않는다.
- 카탈로그는 앱과 variant별로 관리한다.
- 화면 목록 수집, 화면 전환 관계 발견, 화면 상태 발견의 분석 범위를 각각 기록한다.
- 99%는 정답 목록이 있는 평가 집합에서 검증할 화면 정의 발견률 목표다. 연결 해석률·경로 발견률·정밀도도 별도 보고하며, 모든 Android 프로젝트에 대한 보장으로 사용하지 않는다.
- 원래 목표인 모든 화면·경로·자원의 자동 분석은 지원 범위를 넓혀 가며 달성할 제품 목표다. 첫 Nav3 어댑터의 완전성 보장과 동일하지 않다.

## 디자인 참조

- [유저 로드 지도](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-61)
- [인바운드 경로 및 디바이스별 트레이스](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-62)
- [함수 호출 및 자원 시계열](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=101-11996)
- [구성 탐색·빌드 의존성](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=161-5059)
