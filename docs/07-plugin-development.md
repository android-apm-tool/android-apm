# 플러그인 개발 시작하기

Android APM의 개발 기반은 두 JVM 모듈과 별도 Android 샘플 빌드로 구성한다. Gradle 플러그인은 빌드에 연결하는 진입점이고, Kotlin 컴파일러 플러그인은 컴파일 중 IR을 읽는 확장이다. 두 플러그인은 개발 머신에서 실행된다. 앱 프로세스에서 동작할 Android 에이전트는 이후 별도 모듈로 구현한다.

## 저장소 구성

```text
android-apm/
 ├─ apm-gradle-plugin/       # 플러그인 적용, 진단 작업, 컴파일러 플러그인 전달
 ├─ apm-compiler-plugin/     # 옵션 처리, K2 registrar, IR 확장
 ├─ samples/compose-app/    # 로컬 플러그인을 사용하는 Compose + Nav3 앱
 ├─ scripts/verify-bootstrap.py
 ├─ gradle/libs.versions.toml
 └─ gradlew
```

`java-gradle-plugin`이 Gradle 플러그인 descriptor를 만들고, 컴파일러 플러그인은 `META-INF/services`로 registrar와 옵션 처리기를 등록한다. `KotlinCompilerPluginSupportPlugin`은 Kotlin 컴파일에 플러그인 JAR과 옵션을 전달한다. 샘플은 `pluginManagement.includeBuild("../..")`로 Gradle 플러그인을 연결하고, 별도 dependency substitution으로 컴파일러 artifact도 로컬 모듈에서 가져온다. Maven Local이나 Plugin Portal에 배포할 필요가 없다. [Gradle 플러그인 개발](https://docs.gradle.org/9.1.0/userguide/implementing_gradle_plugins_binary.html), [Kotlin 컴파일러 플러그인](https://kotlinlang.org/docs/custom-compiler-plugins.html)

## 검증 버전

| 항목 | 버전 |
| --- | --- |
| JDK / JVM target | 17 |
| Gradle Wrapper | 9.1.0, 배포 ZIP SHA-256 검증 |
| Kotlin / Compose compiler | 2.3.21 |
| AGP | 9.0.0 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Compose Foundation | 1.10.4 |
| Activity Compose | 1.10.0 |
| Navigation 3 | 1.0.1 |

최신 버전을 자동 추종하지 않고 이 조합부터 검증한다. 의존 버전은 version catalog에, Gradle 버전은 wrapper 설정에 고정한다. AGP 9의 내장 Kotlin을 사용하며 Android 샘플에 `org.jetbrains.kotlin.android`를 적용하지 않는다. 샘플의 buildscript 의존성으로 KGP를 2.3.21에 맞춘다. [AGP 9의 KGP 버전 설정](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-kgp), [KGP 호환 범위](https://kotlinlang.org/docs/gradle-configure-project.html)

## 개발과 확인

JDK 17을 준비한 뒤 저장소 루트에서 실행한다. 플러그인 자체 빌드에는 Android SDK가 필요 없다.

```bash
./gradlew build
```

컴파일러 테스트는 실제 Kotlin 컴파일러가 패키징된 서비스와 IR 확장을 읽는지 확인한다. Gradle TestKit은 플러그인을 별도 빌드에 적용하고 진단 작업의 configuration cache 재사용을 확인한다.

Android 샘플은 SDK Platform 36과 Build Tools 36.0.0이 필요하다. `ANDROID_HOME`에 SDK 경로를 설정하거나, Git에서 제외한 `samples/compose-app/local.properties`에 `sdk.dir`를 설정한다. Android Studio에서는 샘플 디렉터리를 열면 된다.

```bash
# 설정 확인: 앱 컴파일·실행은 하지 않는다.
./gradlew -p samples/compose-app :app:apmDoctor

# 일반 빌드에서 저장된 IR 진단 결과를 확인한다. --info나 강제 재컴파일은 필요 없다.
./gradlew -p samples/compose-app :app:assembleDebug -Papm.irProbe=true

# UP-TO-DATE, FROM-CACHE, configuration cache와 비활성 debug/release 제외를 검증한다.
python3 scripts/verify-bootstrap.py
```

`apm.irProbe`는 기본값이 false인 개발용 옵션이다. Android Studio에서는 샘플의 `gradle.properties`에 `apm.irProbe=true`를 설정하면 된다. 이름이 정확히 `debug`인 Kotlin compilation에만 적용하며 flavor·테스트 compilation은 지원하지 않는다.

컴파일러는 마지막 IR 호출의 진단 결과를 `app/build/reports/android-apm/debug/ir-probe.txt`에 저장한다. 이 디렉터리는 Kotlin 컴파일 작업의 정식 출력이므로 빌드 캐시에 함께 저장·복원된다. 절대 출력 경로는 컴파일 입력에서 제외한다.

`assembleDebug`·`bundleDebug`와 debug 소스 빌드에는 `apmReportDebug`가 연결된다. 이 작업은 컴파일이 성공한 뒤 저장된 결과를 lifecycle 로그로 출력한다. 컴파일이 `UP-TO-DATE`이거나 `FROM-CACHE`여도 출력 작업은 실행하므로 `--info`, `--rerun-tasks`, `--no-build-cache`, Clean이 필요 없다. 결과만 다시 보려면 `:app:apmReportDebug`를 실행한다. 컴파일 실패 후에는 이전 결과를 출력하지 않으며, 옵션을 끄거나 release를 빌드하면 보고 작업을 연결하지 않는다.

이 파일은 마지막 IR 호출의 스냅샷이다. 증분 컴파일에서는 변경된 일부 파일만 포함할 수 있으며, 전체 소스의 결과를 병합한 화면 카탈로그나 Nav3 entry 연결 결과가 아니다. 여러 컴파일 호출의 결과 병합은 후속 수집기 작업이다.

일반적인 개발 순서는 플러그인 수정 → `./gradlew build` → 샘플 컴파일 → IR 로그 확인이다. 샘플 적용과 컴파일러 SPI 연결은 별도로 검증해, Gradle 설정 문제와 IR 분석 문제를 구분한다.

## 현재 구현 범위와 다음 단계

현재 구현은 Gradle·K2·Compose의 연결 확인용이다. 진단 로그의 Composable 이름은 전체 모듈 목록이나 화면 카탈로그가 아니다. 원본 IR을 변경하지 않으며 화면 발견률, 실제 화면 표시, 함수 계측을 검증한 결과도 아니다.

2026-10-12 로컬 검증 결과:

- `./gradlew build`: 컴파일러 연결 테스트 3개, Gradle TestKit 테스트 1개, Gradle 플러그인 검증 통과.
- Android 샘플의 debug APK 빌드 성공. IR 로그에서 `SampleApp`, `HomeScreen`, `DetailScreen` 확인.
- 옵션을 끈 debug와 옵션을 켠 release를 다시 컴파일해 probe가 실행되지 않음을 확인.
- 현재 IR 로그에는 Compose가 생성한 람다도 포함된다. 수집기의 실행 순서와 변환 전 원본 관계 보존은 별도 PoC에서 검증해야 한다.
- 디바이스 설치·UI 실행, 전체 화면·경로 수집과 99% 발견률 측정은 수행하지 않았다.

GitHub Actions에도 같은 플러그인 테스트와 Android 샘플 컴파일 검증을 구성한다. 테스트 보고서와 컴파일 로그를 artifact로 남긴다.

다음 단계는 variant 설정·생성 소스·다른 컴파일러 플러그인을 재현하는 전체 수집용 컴파일 PoC다. 이어 선언·호출·키·콘텐츠 관계를 모듈 카탈로그로 저장하고, 모듈 간 연결과 분류를 구현한다. 설계의 `apmCollect<Variant>Catalog`, `apmMerge<Variant>Catalog`, `apmRun<Variant>`는 아직 제공하지 않는다. Android 에이전트, 서버, 대시보드와 배포 설정도 후속 작업이다.
