# 설계 문서

기준일: 2026-10-11. 지금까지 논의한 제품 요구사항과 기술 설계를 저장한다.

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [01. 제품 요구사항](01-product-requirements.md) | 용어, 지원 범위, 확정된 화면, 수집 지표 |
| [02. 시스템 구조](02-system-architecture.md) | Gradle, 컴파일러 플러그인, Android 에이전트, 로컬 서버 |
| [03. Nav3 entry 수집](03-nav3-entry-discovery.md) | IR 수집, provider 연결, 모듈 병합, 빌드 캐시 |
| [04. 화면 전환 분석](04-navigation-transition-analysis.md) | 탐색 상태 변경, 콜백 추적, 실행 중 전환 확인 |
| [05. 데이터 모델](05-data-model.md) | 카탈로그, 전환, 트레이스, 측정값, 식별자 |
| [06. 검증 계획](06-validation-plan.md) | 구현 순서, 테스트 시나리오, 미결정 사항 |

## 결정 상태

| 구분 | 내용 |
| --- | --- |
| 확정된 제품 방향 | 프로젝트 플러그인 + localhost, 화면 중심 탐색, 디바이스별 트레이스 |
| 확정된 첫 분석 방향 | Compose + Nav3, Kotlin IR 기반 entry 수집 |
| 기술 설계안 | 모듈별 카탈로그, provider 연결 해석, 정적 전환 분석 + 실행 관측 |
| 구현 전 검증 필요 | AGP/Kotlin/Compose 연동, 전체 수집용 컴파일 구성, 코루틴 인과관계 계측 |
| 아직 미결정 | 지원 버전 조합, 화면 상태 판별 규칙, 자원 수집 API, 저장소·전송 프로토콜 |

기술 설계안은 구현 결과나 성능 보장을 뜻하지 않는다. 개발 중 검증된 결정은 이 표와 해당 문서에 함께 반영한다.

## 읽을 때의 기준

- `선언 발견`, `provider 연결`, `실제 등록`, `실제 표시`는 서로 다른 사실이다.
- 미해결 항목을 삭제하거나 측정하지 않은 값을 0으로 표시하지 않는다.
- 카탈로그는 앱과 variant별로 관리한다.
- 화면 목록 수집, 화면 전환 관계 발견, 화면 상태 발견의 분석 범위를 각각 기록한다.
- 원래 목표인 모든 화면·경로·자원의 자동 분석은 지원 범위를 넓혀 가며 달성할 제품 목표다. 첫 Nav3 어댑터의 완전성 보장과 동일하지 않다.

## 디자인 참조

- [유저 로드 지도](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-61)
- [인바운드 경로 및 디바이스별 트레이스](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=4-62)
- [함수 호출 및 자원 시계열](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=101-11996)
- [구성 탐색·빌드 의존성](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=161-5059)
