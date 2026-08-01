# SceneLog 앱 이미지 (멀티스테이지)
#
# 1단계에서 JDK로 빌드하고 2단계는 JRE만 담는다 —
# 실행 이미지에 gradle·소스·JDK가 없어 작고(≈200MB), 공격 표면도 좁다.
#
# t3.micro(RAM 1GB)에서 빌드할 것을 전제로 gradle 힙을 상한한다.
# (스왑 2GB 추가를 전제로도 기본 힙은 위험 — day6 계획서 참고)

# ── 1단계: 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
# Windows에서 clone/COPY된 경우 CRLF가 sh를 깨뜨린다 — 항상 정규화
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src

# 테스트는 DB 컨테이너가 필요하므로 이미지 빌드에서는 제외 (로컬/CI에서 별도 실행)
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx384m -Dorg.gradle.daemon=false"
RUN ./gradlew bootJar -x test --no-daemon

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

# 기본 힙 상한 — compose의 JAVA_TOOL_OPTIONS가 있으면 그쪽이 우선
ENV JAVA_TOOL_OPTIONS="-Xmx384m"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
