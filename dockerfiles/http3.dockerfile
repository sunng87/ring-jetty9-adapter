FROM sunng/quiche-jdk-17:5

RUN mkdir app
WORKDIR app

COPY target/ring-jetty9-adapter-0.17.7-SNAPSHOT-standalone.jar app.jar
RUN mkdir dev-resources
COPY dev-resources/keystore.jks dev-resources/keystore.jks

ENTRYPOINT java --add-modules jdk.incubator.foreign -jar app.jar
