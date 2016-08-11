FROM sequenceiq/hadoop-docker:2.7.1

# MySQL for Hive metastore
RUN yum install -y mysql-server mysql-connector-java
RUN service mysqld start && \
    mysql -e'GRANT ALL PRIVILEGES ON *.* TO hive@"%" IDENTIFIED BY "hive"; flush privileges;' && \
    mysql -e'GRANT ALL PRIVILEGES ON *.* TO hive@"localhost" IDENTIFIED BY "hive"; flush privileges;'

RUN yum install -y git
RUN curl -s https://archive.apache.org/dist/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz | \
    tar -xz -C /usr/local/ && \
    cd /usr/local && \
    ln -s apache-maven-3.3.9 maven
ENV M2_HOME=/usr/local/maven
ENV PATH=${M2_HOME}/bin:${PATH}

# Cache download of repo and dependencies and unmodified modules
ARG REPO=https://github.com/apache/hive
RUN git clone $REPO
ENV MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=256m -XX:+CMSClassUnloadingEnabled -Dmaven.artifact.threads=1000 "
WORKDIR hive
RUN mvn -T 1000C clean install -DskipTests -Phadoop-2,dist

# Update and build
ARG REVISION=master
RUN git pull && \
    git checkout $REVISION && \
    mvn -T 1.5C clean install -DskipTests -Phadoop-2,dist -o

WORKDIR /

ARG HIVE_VERSION=2.2.0
ENV HIVE_HOME /hive/packaging/target/apache-hive-$HIVE_VERSION-SNAPSHOT-bin/apache-hive-$HIVE_VERSION-SNAPSHOT-bin
ENV PATH $HIVE_HOME/bin:$PATH
ENV HIVE_CONF_DIR /hive/packaging/target/apache-hive-$HIVE_VERSION-SNAPSHOT-bin/apache-hive-$HIVE_VERSION-SNAPSHOT-bin/conf/

COPY hive-site.xml $HIVE_CONF_DIR
COPY hive-env.sh $HIVE_CONF_DIR
COPY hive-log4j2.properties $HIVE_CONF_DIR
COPY my.cnf /etc/

RUN ln -s /usr/share/java/mysql-connector-java.jar $HIVE_HOME/lib
RUN service mysqld start && \
    schematool -dbType mysql -initSchema -verbose

EXPOSE 3306 9083 10000

ENV HIVE_OPTS '-hiveconf mapred.job.tracker=local'

CMD service mysqld start && \
    $HADOOP_HDFS_HOME/sbin/start-dfs.sh && \
    hiveserver2
