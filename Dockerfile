# Stage 1: Build the JAR

FROM gradle:9.1.0-jdk25 AS builder

WORKDIR /app

# Copy project files

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src/ src/

# Make Gradle wrapper executable

RUN chmod +x ./gradlew

# GitHub credentials
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN
ARG GIT_BRANCH=$GIT_BRANCH

# Build with environment variables for GitHub Packages
RUN if [ "$GIT_BRANCH" = "staging" ]; then \
  GITHUB_ACTOR=$GITHUB_ACTOR GITHUB_TOKEN=$GITHUB_TOKEN \
  ./gradlew build -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=staging \
  -DquarkusPluginId=$QUARKUS_PLUGIN_ID -DquarkusPluginVersion=$QUARKUS_PLUGIN_VERSION -x test; \
  else \
  GITHUB_ACTOR=$GITHUB_ACTOR GITHUB_TOKEN=$GITHUB_TOKEN \
  ./gradlew build -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=prod \
  -DquarkusPluginId=$QUARKUS_PLUGIN_ID -DquarkusPluginVersion=$QUARKUS_PLUGIN_VERSION -x test; \
  fi

# Stage 2: Lightweight runtime
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Copy the built JAR from builder stage

COPY --from=builder /app/build/hr-management-backend-0.0.1-runner.jar /app/

# Expose Quarkus port

EXPOSE 8082

# Run the application

ENTRYPOINT ["java","-jar","/app/hr-management-backend-0.0.1-runner.jar"]
