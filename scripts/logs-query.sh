#!/bin/bash
# CloudWatch Logs Insights Query Helper
# 
# Usage: ./scripts/logs-query.sh "query string" [log-group] [start-time] [end-time]
#
# Examples:
#   ./scripts/logs-query.sh "fields @timestamp, @message | limit 5"
#   ./scripts/logs-query.sh "filter @message like /ERROR/ | stats count(*)"
#   ./scripts/logs-query.sh "stats count(*) by bin(5m)"
#
# See LOGS_QUERY_SAMPLES.md for more examples

QUERY="$1"
LOG_GROUP="${2:-/app/realworld-example/dev}"
# Default: today 00:00:00 to now
START_TIME="${3:-$(date -d 'today 00:00:00' +%s)000}"
END_TIME="${4:-$(date +%s)000}"
CONTAINER="realworld-exam_localstack_1"
REGION="ap-southeast-1"

if [ -z "$QUERY" ]; then
    echo "CloudWatch Logs Insights Query Helper"
    echo ""
    echo "Usage: $0 'query string' [log-group] [start-time] [end-time]"
    echo ""
    echo "Arguments:"
    echo "  query       Required. Logs Insights query string"
    echo "  log-group   Optional. Default: /app/realworld-example/dev"
    echo "  start-time  Optional. Epoch ms. Default: today 00:00:00"
    echo "  end-time    Optional. Epoch ms. Default: now"
    echo ""
    echo "Sample Queries:"
    echo "  # Recent logs"
    echo "  $0 'fields @timestamp, @message | sort @timestamp desc | limit 10'"
    echo ""
    echo "  # Find errors"
    echo "  $0 'filter @message like /ERROR/ | sort @timestamp desc | limit 20'"
    echo ""
    echo "  # Count by level"
    echo "  $0 'stats count(*) by bin(5m)'"
    echo ""
    echo "  # Parse and filter"
    echo "  $0 'parse @message \"* * *  [*] *\" as d, t, level, thread, rest | filter level like /ERROR/'"
    echo ""
    echo "See scripts/LOGS_QUERY_SAMPLES.md for more examples"
    exit 1
fi

echo "Log Group: $LOG_GROUP"
echo "Query: $QUERY"
echo "Time Range: $START_TIME - $END_TIME"
echo ""

# Start query
QUERY_ID=$(docker exec $CONTAINER awslocal logs start-query \
  --log-group-name "$LOG_GROUP" \
  --start-time $START_TIME \
  --end-time $END_TIME \
  --query-string "$QUERY" \
  --region $REGION \
  --query 'queryId' --output text 2>/dev/null)

if [ -z "$QUERY_ID" ]; then
    echo "Error: Failed to start query"
    exit 1
fi

echo "Query ID: $QUERY_ID"
echo "Waiting for results..."
echo ""

# Wait for query to complete
sleep 1

# Get results
docker exec $CONTAINER awslocal logs get-query-results \
  --query-id "$QUERY_ID" \
  --region $REGION
