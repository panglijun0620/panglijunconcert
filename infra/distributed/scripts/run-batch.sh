#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 BATCH_ID" >&2
  exit 2
fi

batch_id="$1"
if [[ ! "$batch_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; then
  echo "BATCH_ID must match [A-Za-z0-9][A-Za-z0-9._-]{0,79}" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd "$script_dir/.." && pwd)"
project_root="$(cd "$infra_dir/../.." && pwd)"
env_file="${ENV_FILE:-$infra_dir/.env}"
concert_input="$project_root/data/raw/concerts_upload.csv"
comment_input="$project_root/data/raw/comments_upload.csv"
container_concert_input="/opt/input/raw/concerts_upload.csv"
container_comment_input="/opt/input/raw/comments_upload.csv"
audit_script="$project_root/scripts/audit_final_inputs.py"
compose=(docker compose --env-file "$env_file" -f "$infra_dir/compose.yaml")

required_files=(
  "$env_file"
  "$concert_input"
  "$comment_input"
  "$audit_script"
  "$project_root/spark-jobs/target/concert-analytics-job.jar"
)
for required_file in "${required_files[@]}"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Required file is missing: $required_file" >&2
    exit 3
  fi
done

audit_file="$(mktemp)"
trap 'rm -f "$audit_file"' EXIT
IFS=$'\t' read -r concert_input_count comment_input_count expected_concerts expected_comments <<< "$(
  python3 "$audit_script" \
    --concerts "$concert_input" \
    --comments "$comment_input" \
    --manifest "$audit_file" \
    --format tsv
)"
expected_input="$((concert_input_count + comment_input_count))"
expected_success="$((expected_concerts + expected_comments))"

# Consume each stream fully: with pipefail, an early awk exit would SIGPIPE the Hadoop client.
live_datanodes="$("${compose[@]}" exec -T master hdfs dfsadmin -report \
  | awk -F'[()]' '/Live datanodes/{print $2}')"
running_nodemanagers="$("${compose[@]}" exec -T master yarn node -list -states RUNNING 2>/dev/null \
  | awk -F: '/Total Nodes/{gsub(/[[:space:]]/, "", $2); print $2}')"
if [[ "$live_datanodes" != "2" || "$running_nodemanagers" != "2" ]]; then
  echo "Cluster is not ready: DataNodes=$live_datanodes NodeManagers=$running_nodemanagers" >&2
  exit 4
fi

raw_root="/concert/raw/$batch_id"
if "${compose[@]}" exec -T master hdfs dfs -test -e "$raw_root"; then
  echo "Immutable raw batch already exists: $raw_root" >&2
  exit 5
fi

"${compose[@]}" exec -T master hdfs dfs -mkdir -p \
  "$raw_root/concerts" "$raw_root/comments" "$raw_root/audit"
"${compose[@]}" exec -T master hdfs dfs -put \
  "$container_concert_input" "$raw_root/concerts/concerts.csv"
"${compose[@]}" exec -T master hdfs dfs -put \
  "$container_comment_input" "$raw_root/comments/comments.csv"
"${compose[@]}" exec -T master hdfs dfs -put - "$raw_root/audit/input_manifest.json" < "$audit_file"

"${compose[@]}" exec -T master spark-submit \
  --master yarn \
  --deploy-mode client \
  --class cn.edu.pulseatlas.spark.ConcertBatchJob \
  /opt/jobs/concert-analytics-job.jar \
  --batch-id "$batch_id" \
  --concert-input "hdfs://$raw_root/concerts/concerts.csv" \
  --comment-input "hdfs://$raw_root/comments/comments.csv" \
  --output-root hdfs:///concert

sql="SELECT b.status,b.input_count,b.success_count,
  (SELECT COUNT(*) FROM clean_concert c WHERE c.data_batch_id=b.id),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id AND LOWER(c.source_type) <> 'damai'),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id
      AND c.concert_id IS NOT NULL),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id
      AND c.sentiment_method <> 'lexicon_rule'),
  (SELECT COUNT(*) FROM clean_concert c WHERE c.data_batch_id=b.id AND NOT (
      (LOWER(c.source_type)='piaoniu' AND LOWER(c.source_url) REGEXP '^https://([a-z0-9-]+[.])*piaoniu[.]com(:[0-9]{1,5})?([/?#].*)?$')
      OR (LOWER(c.source_type)='showstart' AND LOWER(c.source_url) REGEXP '^https://([a-z0-9-]+[.])*showstart[.]com(:[0-9]{1,5})?([/?#].*)?$')
      OR (LOWER(c.source_type)='public' AND LOWER(c.source_url) REGEXP '^https://([a-z0-9-]+[.])*(news[.]qq[.]com|bjd[.]com[.]cn|[a-z0-9-]+[.]gov[.]cn)(:[0-9]{1,5})?([/?#].*)?$')
  )),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id
      AND NOT (LOWER(c.source_type)='damai' AND LOWER(c.source_url) REGEXP '^https://([a-z0-9-]+[.])*damai[.]cn(:[0-9]{1,5})?([/?#].*)?$')),
  (SELECT COUNT(*) FROM clean_concert c WHERE c.data_batch_id=b.id AND (c.source_url IS NULL OR c.collected_at IS NULL)),
  (SELECT COUNT(*) FROM clean_comment c WHERE c.data_batch_id=b.id AND (c.source_url IS NULL OR c.collected_at IS NULL))
  FROM analytics_batch b WHERE b.batch_id='$batch_id';"
result="$(printf '%s\n' "$sql" | "${compose[@]}" exec -T mysql sh -lc \
  'db_user="${DB_PUBLISHER_USERNAME:-${MYSQL_USER:-}}"
   db_password="${DB_PUBLISHER_PASSWORD:-${MYSQL_PASSWORD:-}}"
   MYSQL_PWD="$db_password" exec mysql --user="$db_user" --batch --skip-column-names "$MYSQL_DATABASE"')"
IFS=$'\t' read -r status input_count success_count concert_count comment_count foreign_comments linked_comments invalid_sentiment_methods invalid_concert_sources invalid_comment_sources untraceable_concerts untraceable_comments <<< "$result"

if [[ "$status" != "PUBLISHED" \
   || "$input_count" != "$expected_input" \
   || "$success_count" != "$expected_success" \
   || "$concert_count" != "$expected_concerts" \
   || "$comment_count" != "$expected_comments" \
   || "$foreign_comments" != "0" \
   || "$linked_comments" != "0" \
   || "$invalid_sentiment_methods" != "0" \
   || "$invalid_concert_sources" != "0" \
   || "$invalid_comment_sources" != "0" \
   || "$untraceable_concerts" != "0" \
   || "$untraceable_comments" != "0" ]]; then
  echo "Published counts or source checks do not match the validated final inputs." >&2
  echo "status=$status input=$input_count success=$success_count concerts=$concert_count comments=$comment_count foreign_comments=$foreign_comments linked_comments=$linked_comments invalid_sentiment_methods=$invalid_sentiment_methods invalid_concert_sources=$invalid_concert_sources invalid_comment_sources=$invalid_comment_sources untraceable_concerts=$untraceable_concerts untraceable_comments=$untraceable_comments" >&2
  exit 6
fi

echo "Batch $batch_id published: concerts=$concert_count comments=$comment_count"
