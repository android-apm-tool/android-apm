# Android APM

Android 프로젝트에 플러그인을 적용해 화면과 접근 경로를 분석하고, 실제 디바이스에서 측정한 실행 흐름과 자원 사용량을 localhost에서 확인하는 도구.

현재는 설계 단계다. 이 저장소에는 설계 문서만 있으며 플러그인, 수집기, 서버, 대시보드는 아직 구현하지 않았다. 문서에 등장하는 작업 이름과 데이터 형식은 구현 제안이다.

## 시작하기

- [설계 문서 목차](docs/README.md)
- [제품 요구사항과 확정된 화면 구성](docs/01-product-requirements.md)
- [시스템 구조](docs/02-system-architecture.md)
- [IR 기반 화면 발견과 Nav3 연결](docs/03-nav3-entry-discovery.md)
- [화면 전환 관계 분석](docs/04-navigation-transition-analysis.md)
- [데이터 모델](docs/05-data-model.md)
- [검증 계획과 구현 순서](docs/06-validation-plan.md)

## 대상

| 항목 | 조건 |
| --- | --- |
| Android Gradle Plugin | AGP 9.0 기준 |
| 실행 디바이스 | Android 8.0 / API 26 이상 |
| compileSdk | 36 이상 |
| targetSdk | 36 이상 |
| 첫 화면 탐색 어댑터 | Jetpack Compose + Navigation 3 |
| 분석 방식 | Kotlin IR 정적 분석 + 실행 중 관측 |
| 결과 확인 | 로컬 서버가 제공하는 localhost 대시보드 |

Kotlin·Compose·Nav3의 구체적인 지원 버전은 호환성 검증 후 고정한다. 위 조건만으로 모든 버전과 모든 사용자 정의 탐색 코드를 지원한다고 간주하지 않는다.

화면 발견은 전체 Composable 선언 수집, Compose UI 진입점 추적, Nav3 키·콘텐츠 연결을 결합한다. Nav3 밖의 화면 후보와 연결이 미해결인 선언도 보존한다. SDUI를 제외한 지원 범위의 화면 정의 발견률 **99% 이상을 검증 목표**로 두며, 아직 측정한 결과는 없다. 화면 발견률, 연결 해석률, 전환 경로 발견률과 오탐은 [검증 계획](docs/06-validation-plan.md)에 따라 따로 평가한다.

## 디자인

[Figma 최종 확정안](https://www.figma.com/design/qGdhVOiSGSh9VUAMFfl40h/APM?node-id=132-12445)

설계 기준일: 2026-10-12.
