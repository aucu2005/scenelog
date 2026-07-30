# Docker Desktop이 시작 직후 종료되는 문제

## 이슈

로컬 개발 환경 구축 중 Docker Desktop(4.49.0)이 실행 후 수십 초 안에 에러 다이얼로그와 함께 종료됨.
`docker compose up`은 물론 `docker info`조차 간헐적으로만 응답 — 엔진 파이프(`dockerDesktopLinuxEngine`)가 생겼다 사라지기를 반복.
증상만 보면 "Docker가 안 켜진다"이지만, 원인 후보(WSL 문제 / 가상화 설정 / 라이선스 다이얼로그 / 앱 버그)가 많아 로그 없이 추측으로는 진단 불가.

## 원인 (로그로 추적)

`%LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log`에서 종료 직전 에러를 확인:

```
starting services: initializing Inference manager:
listening on unix://...\AppData\Local\Docker\run\dockerInference:
remove ...\dockerInference: The file cannot be accessed by the system.
(listener: The filename, directory name, or volume label syntax is incorrect.)
```

- Docker Desktop의 **Inference manager**(AI 모델 실행 기능)가 초기화 중, 이전 실행이 남긴
  **유닉스 도메인 소켓 파일(`dockerInference`)을 삭제하지 못해** 기동 전체가 실패.
- 이 파일은 손상된 AF_UNIX 소켓(NTFS 리파스 포인트)으로, 일반 `dir`에는 보이지 않고
  `del \\?\...`(확장 경로), `fsutil`로도 삭제 불가 — **Error 1920: The file cannot be accessed by the system**.
- 즉 "Docker가 안 켜진다"의 실제 원인은 프로젝트와 무관한 **부가 기능의 잔여 파일 하나**였다.

## 대응

1. **로그를 먼저 봤다** — 추측(재설치, WSL 리셋)으로 시작했다면 수 시간을 썼을 것.
   에러 다이얼로그의 원문이 백엔드 로그에 그대로 남아 있었다.
2. 삭제 불가 파일은 **부모 디렉토리 rename으로 우회**:
   `Docker\run` → `Docker\run_broken_20260731` (삭제는 파일 핸들 검사에 막히지만 rename은 디렉토리 엔트리 변경이라 통과).
   Docker가 다음 실행에서 `run/`을 새로 만들면서 깨끗한 상태 확보.
3. Docker Desktop을 **최신 버전으로 업데이트** (4.49.0의 알려진 크래시 버그 대응 포함).
4. 재실행 후 정상 기동 확인 — compose 컨테이너 3개 healthy, 애플리케이션 `/actuator/health` 200.

## 재발 방지

- Docker Desktop 문제는 **`%LOCALAPPDATA%\Docker\log\host\` 로그부터 확인**하는 것을 표준 절차로.
- "앱이 안 켜진다" 류의 문제에서 재설치는 최후 수단 — 로그에 원인이 명시된 경우가 대부분.
- 이 경험은 EC2 배포일(§8-A)의 OOM 진단(`dmesg` 확인)과 같은 원리다:
  **증상이 아니라 로그가 가리키는 지점을 고친다.**
