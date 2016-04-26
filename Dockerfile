FROM sequenceiq/hadoop-docker:2.7.1

ARG REPO=https://github.com/apache/hive
ARG REVISION=master

ARG DEBUG_PORT=8000

# MySQL for Hive metastore
RUN yum install -y mysql-server mysql-connector-java
RUN service mysqld start && \
    mysql -e'GRANT ALL ON *.* TO hive@"%" IDENTIFIED BY "hive"; flush privileges;'

RUN yum install -y git
RUN curl -s https://archive.apache.org/dist/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz | \
    tar -xz -C /usr/local/ && \
    cd /usr/local && \
    ln -s apache-maven-3.3.9 maven
ENV M2_HOME=/usr/local/maven
ENV PATH=${M2_HOME}/bin:${PATH}

RUN git clone $REPO && \
    cd hive && \
    git checkout $REVISION && \
    mvn clean install -DskipTests -Phadoop-2,dist

ENV HIVE_HOME /hive/packaging/target/apache-hive-2.1.0-SNAPSHOT-bin/apache-hive-2.1.0-SNAPSHOT-bin
ENV PATH $HIVE_HOME/bin:$PATH
ENV HIVE_CONF_DIR /hive/packaging/target/apache-hive-2.1.0-SNAPSHOT-bin/apache-hive-2.1.0-SNAPSHOT-bin/conf/

COPY hive-site.xml hive/packaging/target/apache-hive-2.1.0-SNAPSHOT-bin/apache-hive-2.1.0-SNAPSHOT-bin/conf/
COPY my.cnf /etc/

RUN ln -s /usr/share/java/mysql-connector-java.jar $HIVE_HOME/lib
RUN service mysqld start && \
    schematool -dbType mysql -initSchema -verbose

EXPOSE 3306 9083 10000

ARG HIVE_OPTS='-hiveconf mapred.job.tracker=local'

CMD service mysqld start && \
    hive --service hiveserver2 --debug[port=[DEBUG_PORT],mainSuspend=y,childSuspend=y]
