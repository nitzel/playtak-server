FROM maven:3-eclipse-temurin-21-alpine as base

FROM base as dev
RUN apk add npm
RUN npm install -g amvn

WORKDIR /app

COPY ./pom.xml ./
# Download dependencies here to prevent amvn from re-downloading them on every startup
RUN mvn dependency:resolve
# amvn will rebuild+restart the server when .java files change
# Logged in users (not guests) will reconnected by the UI within ~12s.

# consider removing clean for slightly faster compilation
ENTRYPOINT amvn clean package exec:java --watch

FROM base as production
VOLUME /tmp
WORKDIR /tmp/
COPY ./pom.xml /tmp/
RUN mvn dependency:resolve

COPY src /tmp/src
RUN mvn compile
RUN mvn package

VOLUME /playtakdb
ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS
# COPY target/takserver-jar-with-dependencies.jar takserver.jar  # this could be used when the jar file is built locally
COPY ./properties.xml /tmp/target/
WORKDIR /tmp/target
ENTRYPOINT exec java $JAVA_OPTS -jar takserver-jar-with-dependencies.jar
