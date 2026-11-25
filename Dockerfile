FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .

# Use a mirror fallback if Maven Central returns 429
RUN mkdir -p /root/.m2 && printf '%s\n' \
  '<settings>' \
  '  <mirrors>' \
  '    <mirror>' \
  '      <id>aliyun-central</id>' \
  '      <mirrorOf>central</mirrorOf>' \
  '      <url>https://maven.aliyun.com/repository/central</url>' \
  '    </mirror>' \
  '  </mirrors>' \
  '</settings>' > /root/.m2/settings.xml

COPY . .

RUN sh -c 'for i in 1 2 3 4; do \
      mvn clean package -DskipTests -B && exit 0; \
      echo "Build attempt $i hit rate limit/error; retrying in 10s..."; \
      sleep 10; \
    done; exit 1'

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]