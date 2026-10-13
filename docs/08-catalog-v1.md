# 정적 UI 카탈로그 — 읽기용 JSON v2

Kotlin IR에서 UI 선언과 Nav3 등록을 수집하고, 모듈 간 호출 관계를 연결한다. 공개 보고서는 [catalog-report-v2.schema.json](../schema/catalog-report-v2.schema.json)을 따르며 2칸 들여쓰기로 출력한다. 런타임 화면 방문 기록이나 성능 측정 이벤트는 포함하지 않는다. 병합용 내부 데이터는 기존 [catalog-v1.schema.json](../schema/catalog-v1.schema.json)을 유지한다.

이 분리를 선택한 이유와 샘플·룰업의 실제 검증 결과는 [카탈로그 구현 결정과 검증](09-catalog-decisions-and-validation.md)에 정리한다.

## 실행

APM Gradle 플러그인을 앱과 분석할 의존 모듈에 적용한다. 로컬 개발에서는 [개발 문서](07-plugin-development.md)의 두 `includeBuild` 설정을 사용한다. Android 모듈은 `compileDebugKotlin`, Kotlin/JVM 모듈은 `compileKotlin`의 입력을 사용한다. flavor가 붙은 variant와 Kotlin Multiplatform은 아직 지원하지 않는다.

```powershell
# 현재 모듈만 수집
.\gradlew.bat :app:apmCollectDebugCatalog
# 앱의 debugCompileClasspath가 선택한 프로젝트 의존 모듈까지 연결
.\gradlew.bat :app:apmMergeDebugCatalog
```

산출물:

- 모듈별: `<module>/build/reports/android-apm/catalog/debug/catalog.json`
- 앱 병합: `app/build/reports/android-apm/catalog/debug/project-catalog.json`
- 컴파일 중간 결과: `<module>/build/intermediates/android-apm/debug/catalog/module.json`
- 병합 입력 원본: `<module>/build/intermediates/android-apm/debug/published/catalog.json`

`apm.irProbe=true`는 이전의 간단한 빌드 로그 출력 옵션이다. 카탈로그 작업에는 필요하지 않다. 일반 assemble 작업의 입력·출력과 카탈로그 작업의 출력은 분리한다.

## 결과 읽기

| 필드 | 의미 |
| --- | --- |
| `summary` | 모듈·소스·수집 Composable·참조 Composable·entry·경로 후보·진단 수 |
| `screenCandidates` | 경로 인자, content 함수에서 직접 호출하는 함수 이름, 소스, 분류·해석 상태 |
| `entryBindings` | Nav3 provider 문맥별 키 후보, 등록 위치·조건, 분류·해석 상태 |
| `uiRoots` | Activity/ComposeView의 setContent 위치와 NavDisplay 연결 |
| `navHosts`, `providers` | NavDisplay와 provider의 ID·소스·연결 |
| `composables` | 실제 UI 연결에서 참조한 함수의 ID·이름·선언 소스. 외부 함수는 `external: true` |
| `diagnostics` | 진단 코드별 개수와 중복을 제거한 소스 위치 |
| `modules` | 분석에 포함된 모듈 ID |
| `missingModules` | 병합 대상 프로젝트 의존성 중 APM 산출물이 없는 모듈 |

`contentCalls`는 람다 본문에서 직접 호출하는 Composable 이름이며, 이름 있는 Composable 함수 참조는 해당 함수 이름을 기록한다. 첫 함수를 화면으로 단정하지 않는다. `composableIds`는 content 전체에서 도달하는 함수의 ID를 중복 없이 참조하며, 상세 정보는 `composables`에서 한 번만 기록한다. Frame/Scaffold 같은 래퍼와 내부 컴포넌트가 함께 포함될 수 있다. 오버로드는 서로 다른 ID를 유지한다. UI 이벤트 콜백을 화면 전이로 분류하지 않는다.

공개 JSON에는 전체 함수·변수·호출 그래프, 긴 내부 시그니처, 반복된 인자 및 바이트 오프셋을 넣지 않는다. 위치는 `path`와 `line`만 남긴다. 원시 호출 데이터, 호출별 조건·인자와 함수 선언 전체는 병합용 중간 파일에서 확인할 수 있다. 공개 JSON을 병합 입력으로 사용하지 않는다.

같은 등록 함수를 두 provider에서 호출하면 binding을 따로 만든다. 제네릭 키는 호출 지점의 타입 인자를 대입한다. 상수 경로 인자는 `arguments.path`에 문자열로 표시하며, 계산되는 경로는 표현식 종류·이름·소스만 남긴다. 표현식 요약은 실행 결과가 아니다. 실제 조건 판정·콜백 실행을 추측하지 않는다.

`summary.collectedComposables`는 전체 수집 선언 수이고, `summary.referencedComposables`는 보고서의 UI 연결이 참조한 함수 수다. 후자는 외부 라이브러리 함수도 포함할 수 있으므로 둘은 화면 수나 커버리지가 아니다. 호출되지 않은 preview·도우미 선언은 공개 목록에서 제외한다.

### 기존 v1 소비 코드 이전

파일 경로는 유지하며 `schemaVersion`은 2로 바뀐다. `entryDeclarations` 수는 `summary.entryDeclarations`, `uiRootBindings`는 `uiRoots`, `navHostBindings`는 `navHosts`에서 읽는다. 반복된 `composableCalls` 대신 `composableIds`를 `composables`와 연결한다. 원시 v1 분석이 필요하면 위의 병합 입력 원본을 읽는다.

`screenCandidates[].classification=UNCLASSIFIED`는 화면 경계가 확정되지 않았다는 뜻이다. 경로 테이블의 여러 render 람다가 발견되어도 경로별 실제 선택을 확정하지 않는다. `NAV3_DESTINATION_CONTENT`는 provider에 등록된 content 경계를 뜻하며 개별 Composable 모두가 화면이라는 뜻이 아니다.

## 상태와 지원 범위

`collectionStatus=COMPLETE_FOR_DECLARED_INPUTS`는 해당 전체 Kotlin 컴파일의 입력 순회를 완료했다는 뜻이다. 모든 화면을 발견했다는 보장은 아니다. 방문한 파일 수는 `summary.sourceFiles`, 전체 목록은 내부 v1의 `scope.files`에 기록하며 테스트 소스는 포함하지 않는다. Java 메서드 본문은 분석하지 않는다.

`resolutionStatus=PARTIAL` 및 개별 binding의 `resolution`을 확인해야 한다. 동적 키·분기·가상 호출·mutable 값·외부 라이브러리 본문·DI 주입 그래프는 정적 해석의 한계가 있다. 모듈 카탈로그를 병합하면 로컬 의존 모듈의 본문을 추가로 연결할 수 있다. 바이너리만 있는 외부 라이브러리는 Composable 호출 사실과 전달된 슬롯을 보존한다.

Nav3 runtime 1.0.1/1.1.2의 entryProvider, EntryProviderScope.entry/addEntryProvider, NavEntry와 직접 provider/fallback 형태를 대상으로 한다. 사용자 코드의 이름만 같은 entry 함수는 Nav3로 취급하지 않는다. content 매개변수를 찾지 못하는 Nav3 호출은 `UNSUPPORTED_NAV3_SIGNATURE`를 남긴다. 검증 범위 밖의 API 호환성을 보장하지 않는다.

DI 기여의 qualifier/component별 자동 주입 연결과 완전한 화면/컴포넌트 분류기는 후속 범위다. DI 등록 선언을 발견하더라도 주입될 provider를 추측해서 연결하지 않는다. `UNRESOLVED_DI_BINDING`으로 남긴다. 함수 흐름 깊이 40, 순회 단계 5,000, 표현식 깊이 24를 넘으면 `ANALYSIS_LIMIT_REACHED`를 기록한다.

## 캐시와 실패 처리

`apmCompileDebugCatalog`만 증분 컴파일을 끄고 별도 디렉터리에서 전체 컴파일한다. 앱의 일반 `compileDebugKotlin` 설정은 바꾸지 않는다. 원래 컴파일의 소스·Java 소스·생성 작업 의존성·클래스패스·friend 경로·컴파일러 옵션·플러그인 옵션을 재사용한다. APM 수집기는 Compose lowering 전에 실행된다.

Gradle이 입력 변경과 작업 출력을 추적하므로 변경이 없으면 UP-TO-DATE, 출력 삭제 후 캐시가 있으면 FROM-CACHE를 사용한다. 소스를 삭제하거나 entry가 0개가 되면 이전 선언을 제거한 전체 스냅샷으로 교체한다. 입력 파일이 전혀 없어도 빈 카탈로그를 발행한다.

공개 보고서는 컴파일 성공 후 별도 작업이 원자적으로 교체한다. 컴파일에 실패하면 작업이 실패하고 마지막 성공 보고서는 그대로 남는다. 실패 직후 남아 있는 파일을 이번 실행의 새 결과로 취급하면 안 된다. 카탈로그에는 타임스탬프나 절대 프로젝트 경로를 넣지 않는다. pretty 출력과 중복 제거도 결정적이므로 동일 입력의 파일 바이트는 동일하다. 원본과 공개 파일은 서로 다른 작업 출력 경로로 추적하며 캐시를 비활성화하지 않는다.

## 검증

```powershell
.\gradlew.bat build
python scripts/verify-catalog.py
python scripts/verify-bootstrap.py
python scripts/verify-consumer.py C:\path\to\consumer
```

`verify-consumer.py`는 Kotlin DSL을 사용하는 소비 프로젝트의 settings와 루트 build 스크립트에 로컬 플러그인을 임시 적용하고, 종료 시 원래 바이트로 복원한다. 로그는 플러그인 프로젝트의 `build/consumer-verification`에 저장한다. 앱 소스나 Manifest는 수정하지 않는다.
