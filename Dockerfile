FROM eclipse-temurin:25-jdk-noble AS build
RUN apt-get update && apt-get install -y curl unzip
WORKDIR /app
COPY . .
RUN ./mill prepareOffline
RUN ./mill assembly

FROM eclipse-temurin:25-jre-alpine-3.22
WORKDIR /app
COPY --from=build /app/out/assembly.dest/out.jar .
COPY web ./web
RUN mkdir -p data
EXPOSE 8080
CMD ["java", "-jar", "out.jar"]
