FROM eclipse-temurin:21-jdk-jammy AS build
RUN apt-get update && apt-get install -y curl gzip && rm -rf /var/lib/apt/lists/* \
 && curl -sSLf https://github.com/VirtusLab/scala-cli/releases/download/v1.14.0/scala-cli-x86_64-pc-linux.gz \
  | gzip -d > /usr/local/bin/scala-cli \
 && chmod +x /usr/local/bin/scala-cli
WORKDIR /app
COPY server.scala .
RUN scala-cli package server.scala --assembly --main-class Main --output payto.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/payto.jar .
COPY web ./web
RUN mkdir -p data
EXPOSE 8080
CMD ["java", "-jar", "payto.jar"]
