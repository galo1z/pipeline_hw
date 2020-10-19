FROM openjdk:11.0.1-jre-slim-stretch

RUN useradd --create-home -U webgoat

USER webgoat

COPY webgoat-server/target/webgoat-server-v8.1.0.jar /home/webgoat/webgoat.jar
COPY start.sh /home/webgoat/

EXPOSE 8080

ENTRYPOINT /bin/bash /home/webgoat/start.sh
