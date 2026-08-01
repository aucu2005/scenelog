# t3.micro 배포 — "거의 확실히 만난다"던 OOM을 만나지 않은 이유

> 트러블슈팅 로그 7호 · 2026-08-01 · day6 EC2 배포. 사후 대응이 아니라 **예방이 성공한 기록**

## 상황

RAM 1GB짜리 t3.micro에 컨테이너 4개(Spring Boot + PostgreSQL + MongoDB + Redis)를 올리고,
같은 기계에서 Gradle 빌드까지 했다. day6 계획서는 이렇게 경고했다:

> "합계 1GB 초과 → 컨테이너가 조용히 죽는다. 애플리케이션 로그에 예외가 안 남는다."

배포 후 실측:

```
$ sudo dmesg | grep -i "killed process"
(없음 — OOM Killer 발동 0회)

$ free -m                 →  Mem 477/916 사용, Swap 348/2047 사용
$ docker stats            →  app 262MiB/640  mongo 126/384  postgres 28/256  redis 5/96
```

빌드·ETL 200건·시뮬레이션·집계 전 과정에서 **한 번도 죽지 않았다.**

## 왜 안 죽었나 — 4겹의 선제 대응

| 겹 | 조치 | 언제 넣었나 |
|---|---|---|
| ① swap 2GB | Terraform user_data에서 **부팅 시 자동 생성** + fstab 등록 | 인스턴스가 뜨기 전 |
| ② JVM 힙 상한 | `JAVA_TOOL_OPTIONS=-Xmx384m` (기본값이면 JVM이 "메모리 넉넉하네" 하고 수백 MB를 집는다) | day1 compose 설계 |
| ③ 컨테이너별 mem_limit | app 640m · mongo 384m(+WiredTiger 캐시 0.25GB) · pg 256m · redis 64mb LRU | day1 compose 설계 |
| ④ 로컬 사전 검증 | **배포 전에 로컬에서 같은 compose 제약으로 4컨테이너를 띄워봤다** | 배포 당일 아침 |

핵심 판단은 ①과 ④다. swap은 "문제를 겪은 뒤 SSH로 들어가 넣는 것"이 정석 코스지만,
day5 시드(120만 건) 때 Gradle 빌드가 메모리를 얼마나 먹는지 이미 봤기 때문에
**user_data에 넣어 사람이 개입할 틈 없이 해결**했다. ④는 "서버에서 처음 시도하면
디버깅이 훨씬 어렵다"는 계획서 지침 그대로 — 로컬에서 healthy를 확인한 구성을 그대로 가져갔다.

> **교훈**: 장애 대응 경험만 가치가 아니다. **예방이 성공하면 dmesg의 빈 출력이 증거가 된다.**
> 단, 예방이 성공했음을 증명하려면 "안 났다"를 실측으로 남겨야 한다 (free/dmesg/docker stats).

## 그날 실제로 겪은 문제 2건

### ① clone 시점과 push 시점의 어긋남

user_data가 저장소를 clone한 시점은 **Dockerfile을 아직 push하기 전**이었다.
그대로 빌드했다면 "Dockerfile 없음"으로 실패했을 것. 빌드 시작 직후 알아채고
중단 → push → `git pull` → 재시작으로 해결 (몇 분 손실).

- **원인**: 인프라 자동화(user_data의 clone)와 수동 작업(커밋·push)의 순서 의존성을 놓침
- **재발 방지**: 배포 스크립트가 클론"만" 하지 말고 시작 시점에 `git pull`을 포함하게 한다
  — 실제로 이후 재배포 절차를 `git pull && docker compose --profile app up -d --build`로 통일

### ② 원격 first-run에서만 드러난 API 계약

EC2에서 데모 계정 생성 시 `POST /api/auth/signup`이 **400**을 반환했다.
로컬에서는 계정이 이미 있어서(옛날에 만들어 둠) 이 경로를 오래 안 탔던 것.
원인은 단순 — `nickname` 필드 누락. 하지만 교훈은 단순하지 않다:

> **"로컬에서 되던 것"에는 로컬에 쌓인 상태가 포함돼 있다.** 깨끗한 환경에 배포하는 순간
> 상태 의존이 전부 드러난다. 첫 배포는 기능 테스트가 아니라 **상태 의존성 테스트**다.

## 배포 검증 절차 (재사용용)

```
① 인프라: terraform apply → 출력의 public_ip 확보
② 초기화: cloud-init status --wait → swap/docker/clone 확인
③ 시크릿: .env를 SSH stdin으로 전달 (로컬과 다른 비밀번호, user_data에 넣지 않는다)
④ 기동:   docker compose --profile app up -d --build → 4컨테이너 healthy
⑤ 외부:   공인IP로 /actuator/health UP + swagger 200
⑥ 데이터: ETL(193건 적재·5건 격리) → 시뮬레이션(1,646건) → 집계 → 검출 2/2 확인
⑦ 메모리: free/dmesg/docker stats — OOM 없음을 실측으로 기록
```

## 보안에서 지킨 선

- 8080만 공개. **5432/27017/6379는 보안그룹에서 아예 열지 않음** — DB는 앱 뒤에만 존재
- SSH(22)는 내 IP `/32`로만
- EC2의 `.env`는 로컬과 **다른 비밀번호** + 시크릿은 user_data(콘솔에서 평문 조회 가능)가 아니라 SSH로만 전달
