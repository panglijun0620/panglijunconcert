export JAVA_HOME=/opt/java/openjdk
export HADOOP_HEAPSIZE_MAX=512
export HADOOP_OPTS="${HADOOP_OPTS:-} --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
export HDFS_NAMENODE_OPTS="-Xms256m -Xmx512m"
export HDFS_DATANODE_OPTS="-Xms128m -Xmx384m"
export YARN_RESOURCEMANAGER_HEAPSIZE=512
export YARN_NODEMANAGER_HEAPSIZE=512
export HADOOP_LOG_DIR=/var/log/hadoop
export HADOOP_PID_DIR=/tmp/hadoop-pids
