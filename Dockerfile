FROM eclipse-temurin:25-jdk-noble AS build
RUN apt-get update && apt-get install -y curl unzip
WORKDIR /app
COPY scala .
RUN ./scala compile .
COPY . .
RUN ./scala package server.scala --power --assembly --output payto.jar

FROM eclipse-temurin:25-jre-alpine-3.22
WORKDIR /app
COPY --from=build /app/payto.jar .
COPY web ./web
RUN mkdir -p data
EXPOSE 8080
CMD ["java", "-jar", "payto.jar"]
