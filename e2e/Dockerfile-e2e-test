FROM openjdk:11-jdk

RUN mkdir -p /tmp/spp-probe

ADD ./spp-probe.yml /tmp/spp-probe
ADD ./spp-probe-*.jar /tmp/spp-probe/spp-probe.jar

ENV SPP_DELETE_PROBE_DIRECTORY_ON_BOOT=false

ADD ./VariableTests.java /

RUN javac -g VariableTests.java

CMD [ "java", "-javaagent:/tmp/spp-probe/spp-probe.jar", "VariableTests" ]