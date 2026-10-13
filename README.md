# Android APM

Android 프로젝트에 플러그인을 적용해 화면과 접근 경로를 분석하고, 실제 디바이스에서 측정한 실행 흐름과 자원 사용량을 localhost에서 확인하는 도구.

Gradle/K2 IR 플러그인으로 Composable과 Nav3 entry·content 관계를 수집하고 모듈별 JSON 카탈로그를 생성·병합한다. Compose·Nav3 샘플 앱과 캐시 검증 스크립트를 포함한다. 실행 에이전트, 서버, 대시보드는 아직 구현하지 않았다. 정적 수집기의 지원 범위와 미해결 관계는 [카탈로그 v1](docs/08-catalog-v1.md)에 명시한다.

## 시작하기

- [플러그인 개발 환경과 빌드 방법](docs/07-plugin-development.md)
- [카탈로그 실행·JSON 형식·지원 범위](docs/08-catalog-v1.md)
- [설계 문서 목차](docs/README.md)
- [제품 요구사항과 확정된 화면 구성](docs/01-product-requirements.md)
- [시스템 구조](docs/02-system-architecture.md)
- [IR 기반 화면 발견과 Nav3 연결](docs/03-nav3-entry-discovery.md)
- [화면 전환 관계 분석](docs/04-navigation-transition-analysis.md)
- [데이터 모델](docs/05-data-model.md)
- [검증 계획과 구현 순서](docs/06-validation-plan.md)

JDK 17에서 플러그인을 빌드하고 테스트한다. 샘플 검증에는 Android SDK 36과 `ANDROID_HOME` 설정이 추가로 필요하다.

```bash
./gradlew build
./gradlew -p samples/compose-app :app:apmDoctor
./gradlew -p samples/compose-app :app:apmMergeDebugCatalog
python3 scripts/verify-bootstrap.py
python3 scripts/verify-catalog.py
```

개발용 IR probe는 기본 비활성 상태이며 `-Papm.irProbe=true` 또는 `gradle.properties`의 `apm.irProbe=true`로 켠다. 일반 debug 빌드에서 마지막 IR 진단 결과를 출력하며, 컴파일이 `UP-TO-DATE`이거나 `FROM-CACHE`여도 결과를 확인할 수 있다. 샘플은 included build로 로컬 플러그인을 사용한다. 아직 외부 배포한 플러그인이 아니다.

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
