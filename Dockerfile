# STAGE 1 Build (빌드 환경)
FROM gradle:8.5-jdk21 AS builder

# STAGE 1 Build (빌드 환경 - 작업 dir)
WORKDIR /shgapp

# STAGE 1 Build (빌드 환경 - 현재 dir 전체를 /app으로 복사)
COPY . .

# STAGE 1 Build (빌드 환경 - jar 파일 생성)
RUN gradle build -x test --no-daemon


# STAGE 2 RUN (실행 환경 - jre만 포함 서버 띄우기)
FROM eclipse-temurin:21-jre-jammy

# STAGE 2 RUN (실행 환경 - jar 실행용 user 생성)
RUN groupadd -r shgapp && useradd -r -g shgapp shgapp

# STAGE 2 RUN (실행 환경 - 1에서 만든 jar 복사)
COPY --from=builder /shgapp/build/libs/*.jar shg-app.jar

# STAGE 2 RUN (실행 환경 - jar 소유권 변경)
RUN chown shgapp:shgapp shg-app.jar

RUN mkdir -p /logs && chown shgapp:shgapp /logs

USER shgapp

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "shg-app.jar"]
