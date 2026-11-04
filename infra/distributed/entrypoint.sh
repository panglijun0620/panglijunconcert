#!/usr/bin/env bash
set -euo pipefail

role="${NODE_ROLE:-}"

wait_for_port() {
  local host="$1"
  local port="$2"
  local attempts="${3:-60}"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if nc -z "$host" "$port"; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for ${host}:${port}" >&2
  return 1
}

wait_for_processes() {
  local pid_files=("$@")
  while true; do
    for pid_file in "${pid_files[@]}"; do
      if [[ ! -s "$pid_file" ]] || ! kill -0 "$(<"$pid_file")" 2>/dev/null; then
        echo "Hadoop daemon exited: ${pid_file}" >&2
        exit 1
      fi
    done
    sleep 10
  done
}

stream_logs() {
  tail -n 0 -F "$HADOOP_LOG_DIR"/*.log 2>/dev/null &
}

initialize_hdfs_paths() {
  for _ in {1..60}; do
    if hdfs dfs -mkdir -p \
        /concert/raw /concert/clean /concert/result /spark-events /tmp/logs \
        /tmp/hadoop-yarn/staging/history/done_intermediate /mr-history/done 2>/dev/null; then
      hdfs dfs -chmod 1777 /spark-events /tmp/logs
      return 0
    fi
    sleep 2
  done
  echo "HDFS did not leave safe mode in time" >&2
  return 1
}

case "$role" in
  master)
    if [[ ! -f /data/hdfs/namenode/current/VERSION ]]; then
      hdfs namenode -format -nonInteractive -force -clusterId concert-single-server
    fi
    hdfs --daemon start namenode
    yarn --daemon start resourcemanager
    initialize_hdfs_paths
    mapred --daemon start historyserver
    stream_logs
    wait_for_processes \
      "$HADOOP_PID_DIR/hadoop-hadoop-namenode.pid" \
      "$HADOOP_PID_DIR/hadoop-hadoop-resourcemanager.pid" \
      "$HADOOP_PID_DIR/hadoop-hadoop-historyserver.pid"
    ;;
  worker)
    wait_for_port master 9000
    hdfs --daemon start datanode
    yarn --daemon start nodemanager
    stream_logs
    wait_for_processes \
      "$HADOOP_PID_DIR/hadoop-hadoop-datanode.pid" \
      "$HADOOP_PID_DIR/hadoop-hadoop-nodemanager.pid"
    ;;
  *)
    echo "NODE_ROLE must be master or worker" >&2
    exit 2
    ;;
esac
