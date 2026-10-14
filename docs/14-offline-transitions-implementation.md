# AI 없는 화면 연결·접근 경로 구현 기록

기준일: 2026-10-15. 전체 목표는 [12번 설계](12-transition-and-access-path-design.md), 보안 계약은 [13번 설계](13-local-analysis-security.md)다. 이 문서는 구현된 범위와 아직 해석하지 못하는 범위를 구분한다.

## 선택 이유

대화에서 요구가 화면 목록 → 누락 원인 → 화면 연결 → 접근 순서로 확장됐다. 연결을 만들려면 함수 이름이나 이벤트 타입이 같다는 사실만으로 부족하다. 클릭에서 전달된 인자, 실제 receiver, collector가 사용하는 stack, 목적지 등록을 같은 문맥에서 이어야 한다. 특히 같은 이벤트 타입을 받는 서로 다른 Channel을 연결하면 존재하지 않는 경로가 만들어진다.

보안 요구에 따라 제품 실행에는 AI 모델·LLM API·embedding·외부 검색·원격 분석 fallback을 추가하지 않았다. 기존 Kotlin IR와 SootUp/Heros를 사용하고 Android/Compose/Hilt/Channel의 제한된 의미 규칙을 직접 작성했다. REA 에이전트나 Ghidra를 실행 의존성으로 넣지 않았다. Ghidra/JADX는 실제 바이너리 미해결 사례가 있을 때 별도 adapter로 검토한다.

호출 후보를 그대로 화면 연결로 바꾸지 않는다. SootUp/Heros의 후보와 소스의 정확한 override signature를 receiver의 구현 클래스와 대조한다. 객체를 입증하지 못한 virtual dispatch는 미해결이다. 채널 이동에는 동일 stream identity, 활성 collector, 같은 host의 stack, 수신 callback의 실제 stack 효과가 필요하다. 전달 성공은 정적 분석으로 관측한 사실이 아니므로 조건으로 남긴다.

## 파이프라인

```mermaid
flowchart TD
    P[관리자가 준비한 도구·SDK·의존성] --> H[파일별 SHA-256 bundle 검증]
    N[controller network namespace] --> H
    H --> S[합성 외부 접속 차단 probe]
    S --> C[격리 복사: secret 설정·build·symlink 제외]
    C --> W[별도 worker namespace + seccomp]
    W --> G[Gradle offline 설정·KSP·수집 컴파일]
    G --> K[Kotlin IR 기본값·receiver·분기·대입·DI]
    G --> B[SootUp CFG + Heros callback 후보]
    K --> L[카탈로그·호출 근거 병합]
    B --> L
    L --> T[문맥별 trigger → 값 전달 → ordered stack 효과]
    M[merged Manifest] --> E[launcher·explicit Intent·filter 구분]
    L --> E
    T --> A[조건·stack을 유지하는 제한 BFS]
    E --> A
    A --> O[새 결과 세대: transitions·entries·paths]
    W --> X[worker 밖 strace 감사·종료 검사]
    X --> F[성공 때만 last-success.json 교체]
    O --> F
```

수집 컴파일은 기존 앱 컴파일과 분리한다. SootUp CFG는 normal/exception successor와 source bridge 상태를 기록한다. 전환 해석기는 source IR 제어 흐름과 프레임워크 요약을 사용한다. JVM CFG를 내보낸다는 사실이 예외·loop·전체 heap을 모두 해석했다는 의미는 아니다.

## 구현 계약

| 영역 | 구현 | 미지원·보수적 처리 |
| --- | --- | --- |
| IR | 기본 인자, 명시 인자 위치, receiver, bound 함수 참조, 클래스/필드/DI annotation, 정확한 override symbol, 대입, 조건·return target·choice·spread | 알 수 없는 IR 종류와 깊이 제한은 UNKNOWN |
| 목적지 | host/provider/registration 문맥, 유한 map의 key 선택 → 저장된 render 실제 호출 확인 | 동적 map, 실행하지 않은 renderer는 확정 목적지 아님 |
| trigger | 알려진 clickable/Button/BackHandler/LaunchedEffect 슬롯, enabled 조건, callback 전달 | 이름만 비슷한 API 자동 연결 금지; 전역 overlay 아직 미지원 |
| 전달 | 직접 호출, 정확한 receiver override, bound callback, 제한된 CompositionLocal, SingletonComponent의 단일 무자격 Hilt Binds/Inject | qualifier·다중 binding·Providers·unscoped 객체·사용자 ViewModel owner/key는 미해결 |
| Channel | 동일 객체와 실제 collector의 stack capture 증거, 경쟁 collector별 대안, send/trySend 요청·수락/전달 조건 | rendezvous·buffer·취소·실패를 성공 관측으로 표시하지 않음 |
| stack | PUSH, CLEAR, APPEND_ALL, POP의 순서 보존, host identity, choice별 상관관계 | indexed insertion, 일반 리스트, 불명확한 host는 확정 이동 아님 |
| 시작점 | merged Manifest의 launcher, alias, explicit Intent, browsable filter 별도 기록; onCreate→root→host | URI parser 존재만으로 외부 진입 생성 금지 |
| 경로 | launcher 초기 stack에서 BFS, 분기 모순 제거, 최대 깊이/상태/경로/stack suffix | 복원·cold/warm·인증 후 pending 소비 전체 상태 머신은 미지원 |
| 상태·예외 | 상태 버전 계약, early/nonlocal return, 미지원 쓰기/try/loop 진단 | 상태 쓰기의 일반 heap/reducer 해석, runtime 관측은 미구현 |

`RESOLVED_IN_MODEL`은 지원 모델 안에서 목적지가 연결됐다는 뜻이다. 조건·비동기 전달·동적 back은 `MAY`, 대상 불명은 `UNRESOLVED`다. 모든 결과의 실행 관측은 `NOT_OBSERVED`다. `PATH_FOUND_IN_STATIC_MODEL`은 실제 로그인이나 서버 응답을 확인한 결과가 아니다. 경로가 없으면 `NO_PATH_FOUND_IN_EXPLORED_MODEL`과 frontier/coverage를 반환한다.

기본 탐색 제한은 depth 20, states 10,000, target별 paths 5, stack suffix 8이다. 한도에 걸리면 `TRUNCATED`를 유지한다. 함수·값 해석도 단계·깊이 상한이 있으며 잘린 분석을 완전한 결과로 표시하지 않는다.

## 태스크와 파일

일반 개발 환경의 실행 예시다. 아래 Gradle 명령 자체에는 외부 통신 차단 보장이 없다.

```bash
./gradlew -p samples/compose-app :app:apmFindDebugAccessPaths
./gradlew -p samples/compose-app :app:apmFindDebugAccessPaths -Papm.accessQuery=query.json
python3 scripts/verify-transitions.py
```

| 태스크 | 산출물 |
| --- | --- |
| `apmLinkDebugTransitions` | `build/reports/android-apm/catalog/debug/transitions.json` |
| `apmCollectDebugEntryPoints` | 같은 디렉터리의 `entry-points.json` |
| `apmFindDebugAccessPaths` | 같은 디렉터리의 `access-paths.json` |
| 내부 근거 | `build/intermediates/android-apm/debug/analysis/{linked-facts,transition-evidence}.json` |

query 예시는 `{"targetContextRefs":["context:…"],"maxDepth":10,"maxPathsPerTarget":3}`다. target을 생략하면 모든 문맥을 조회한다. query만 바꾸면 수집·전환을 재사용한다. 잘못된 query나 다른 catalog fingerprint의 entry 자료는 거부한다. 내부 근거를 새 규칙으로 재해석할 때는 `dev.androidapm.catalog.TransitionMain`을 격리 runner 안에서 실행할 수 있다.

## 외부 통신 차단 실행

지원 backend는 Linux/WSL2 **x86-64**, 비root 사용자, 승인된 user/network/PID namespace, bubblewrap, strace, OpenJDK 17/21이다. Windows 네이티브 backend는 제공하지 않는다. 필요한 도구가 없거나 namespace를 만들 수 없으면 거부한다. runner가 방화벽/OS 설정을 자동 완화하지 않는다.

관리자가 대상 코드가 없는 준비 환경에서 도구·SDK·의존성을 확보한다. bundle에는 `gradle-home/wrapper/dists`, `gradle-home/caches/modules-2`, `sdk`, 선택적인 공개 도구 `toolkit-stage/project`를 둔다. 분석 중 SDK·Gradle·Maven 다운로드는 허용하지 않는다. 사설 artifact를 포함하면 bundle도 고객 내부 자료로 관리한다. license는 관리자가 별도로 승인한 것을 사용한다.

```bash
# private-root와 bundle은 승인된 Linux 로컬 저장소, 사용자 소유 0700이어야 한다.
python3 scripts/runner_bundle.py /private/apm/bundle
python3 scripts/offline_runner.py \
  --private-root /private/apm --source /path/to/public-or-customer/project \
  --bundle /private/apm/bundle --jdk 21 --timeout 2400 \
  -- /usr/bin/python3 /bundle/toolkit-stage/project/scripts/offline_consumer.py
```

`offline_consumer.py`는 현재 Kotlin DSL root settings/build를 가진 소비 프로젝트의 **복사본**에 included build와 플러그인을 연결하는 검증용 adapter다. 원본을 수정하지 않는다. 다른 빌드 구조는 별도 adapter가 필요하다. 샘플은 worker 안에서 `scripts/offline_gradle.py`를 실행한다. 기본 Gradle heap은 8 GiB이며 준비된 RAM과 앱 크기에 맞게 `--heap`을 명시한다.

controller도 별도의 network namespace에 들어간 후에 소스·로그를 읽는다. 신뢰하는 복사 단계만 원본을 read-only mount하고, 실제 build worker에는 원본 경로를 제공하지 않는다. 원본의 local.properties, keystore.properties, google-services.json, 키 파일, host credentials/sockets와 proxy 환경을 제외한다. 복사본의 SDK 경로와 시험용 inert URL만 생성한다.

worker의 네트워크 namespace에는 외부 인터페이스가 없다. 내부 loopback은 Gradle IPC용으로만 사용하며 host loopback relay에는 닿지 않는다. 별도 PID namespace, 최소 mount, 환경 초기화, seccomp로 ptrace/namespace 변경/CLONE_UNTRACED/io_uring 우회를 제한한다. strace는 worker 밖에서 자식을 추적한다. 외부 connect/send 시도는 차단 뒤 실행 실패로 판정하고 결과를 성공 세대로 공개하지 않는다.

모든 실행은 새 probe·audit·runId를 갖는다. 정책은 `linux-bwrap-v2`, bundle과 accepted input snapshot은 SHA-256으로 식별한다. 실패·취소 자료는 private run에 격리 보관하고 `last-success.json`은 성공한 새 세대만 atomic replace한다. audit/log는 worker가 수정할 수 없고 CLI는 상태/runId만 출력한다. OneDrive·symlink·네트워크 파일시스템·알려진 hosted CI controller는 고객 실행에서 거부한다.

호스트 관리자·커널·system JDK/라이브러리는 신뢰한다. bundle은 실행 전후 해시를 검증하며 외부 host의 악의적인 동시 변경을 하드웨어 수준에서 방어한다고 주장하지 않는다. private 파일의 보존/삭제는 고객 운영 정책에 맡긴다. viewer, Android 테스트 장치, VM snapshot 폐기까지 구현된 기능은 아니다.

## 검증

공개 반례는 `TransitionAnalysisTest`, `CatalogCollectorTest`, `EntryPointsTest`, `test_offline_runner.py`, `verify-offline-gradle.py`에서 재현한다. 정적 모델과 보안 경계는 서로 다른 테스트로 검증한다.

- 일반 리스트/disabled callback/다중 host, 조건부 return, choice 상관관계, reset 후 POP, indexed insertion, 잘못된 시작 조건, 예산 초과.
- map duplicate key의 last-wins, 저장된 renderer의 실제 호출, bound callback과 exact override, Singleton DI와 unscoped 거부, 같은 이벤트의 서로 다른 Channel.
- Manifest parser/filter 구분, alias, XML 외부 엔티티 거부, 다른 catalog의 근거 혼입 거부.
- query-only 캐시, query 실패 시 이전 결과 보존, compiler/Compose 기존 회귀.
- DNS/HTTP/TCP/IPv6/child Python·JVM, host loopback/proxy/socket, secret 파일·symlink·동기화 경로, 감사 우회 syscall, 실패/취소/daemon 자식, bundle 변경, 매 실행 새 감사.
- 실제 Gradle settings/buildSrc/child JVM 전송 시도 3건은 `EGRESS_ATTEMPT_BLOCKED`; cache 누락은 다운로드 없이 `WORKER_FAILED`; 정상 공개 fixture는 `COMPLETED`.

공개 CI는 저장소의 합성 자료만 실행한다. 고객 코드·카탈로그·경로·worker.log/network.trace를 업로드하는 단계는 없다. CI의 OS 설정은 폐기되는 hosted runner의 합성 시험용이며 고객 runner의 보안 정책을 자동 변경하지 않는다.

샘플과 룰업의 최종 실행 수치·제한은 검증 완료 후 이 절에 기록한다. 전체 화면/경로 발견률이나 실제 기기 접근 성공률을 이 테스트 수로 대신하지 않는다.
