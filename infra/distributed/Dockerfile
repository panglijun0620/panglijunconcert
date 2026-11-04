FROM eclipse-temurin:17-jre-jammy

ARG HADOOP_VERSION=3.3.6
ARG SPARK_VERSION=3.5.1
ARG HADOOP_DIST_URL=https://mirrors.tuna.tsinghua.edu.cn/apache/hadoop/common
ARG SPARK_DIST_URL=https://mirrors.huaweicloud.com/apache/spark
ARG HADOOP_SHA512=de3eaca2e0517e4b569a88b63c89fae19cb8ac6c01ff990f1ff8f0cc0f3128c8e8a23db01577ca562a0e0bb1b4a3889f8c74384e609cd55e537aada3dcaa9f8a
ARG SPARK_SHA512=3d8e3f082c602027d540771e9eba9987f8ea955e978afc29e1349eb6e3f9fe32543e3d3de52dff048ebbd789730454c96447c86ff5b60a98d72620a0f082b355

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bash ca-certificates curl netcat-openbsd procps tini \
    && rm -rf /var/lib/apt/lists/*

RUN curl --retry 3 -fsSLo "hadoop-${HADOOP_VERSION}.tar.gz" \
        "${HADOOP_DIST_URL}/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz" \
    && echo "${HADOOP_SHA512}  hadoop-${HADOOP_VERSION}.tar.gz" | sha512sum -c - \
    && tar -xzf "hadoop-${HADOOP_VERSION}.tar.gz" -C /opt \
    && mv "/opt/hadoop-${HADOOP_VERSION}" /opt/hadoop \
    && rm "hadoop-${HADOOP_VERSION}.tar.gz"

RUN curl --retry 3 -fsSLo "spark-${SPARK_VERSION}-bin-hadoop3.tgz" \
        "${SPARK_DIST_URL}/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3.tgz" \
    && echo "${SPARK_SHA512}  spark-${SPARK_VERSION}-bin-hadoop3.tgz" | sha512sum -c - \
    && tar -xzf "spark-${SPARK_VERSION}-bin-hadoop3.tgz" -C /opt \
    && mv "/opt/spark-${SPARK_VERSION}-bin-hadoop3" /opt/spark \
    && rm "spark-${SPARK_VERSION}-bin-hadoop3.tgz"

ENV JAVA_HOME=/opt/java/openjdk \
    HADOOP_HOME=/opt/hadoop \
    HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop \
    SPARK_HOME=/opt/spark \
    SPARK_CONF_DIR=/opt/spark/conf \
    HADOOP_LOG_DIR=/var/log/hadoop \
    HADOOP_PID_DIR=/tmp/hadoop-pids \
    PATH=/opt/spark/bin:/opt/hadoop/bin:/opt/hadoop/sbin:${PATH}

RUN groupadd --gid 10001 hadoop \
    && useradd --uid 10001 --gid hadoop --create-home --shell /bin/bash hadoop \
    && mkdir -p /data/hdfs/namenode /data/hdfs/datanode /data/yarn/local /data/yarn/logs \
        /var/log/hadoop /tmp/hadoop-pids /opt/jobs /opt/input \
    && chown -R hadoop:hadoop /data /var/log/hadoop /tmp/hadoop-pids /opt/hadoop /opt/spark /opt/jobs /opt/input

COPY --chown=hadoop:hadoop hadoop/conf/ /opt/hadoop/etc/hadoop/
COPY --chown=hadoop:hadoop spark-defaults.conf log4j2.properties /opt/spark/conf/
COPY --chown=hadoop:hadoop entrypoint.sh /opt/entrypoint.sh
RUN chmod 0755 /opt/entrypoint.sh

USER hadoop
WORKDIR /home/hadoop
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/entrypoint.sh"]
