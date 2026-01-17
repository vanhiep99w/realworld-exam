#!/bin/bash
# CloudWatch Logs Filter Helper (using filter-log-events API)
# 
# Usage: ./scripts/logs-filter.sh [filter-pattern] [log-group] [limit]
#
# Examples:
#   ./scripts/logs-filter.sh                    # All logs, limit 20
#   ./scripts/logs-filter.sh "ERROR"            # Logs containing ERROR
#   ./scripts/logs-filter.sh "Exception"        # Logs containing Exception
#
# Filter Pattern Syntax:
#   "ERROR"              - Contains word ERROR
#   "ERROR WARN"         - Contains ERROR AND WARN
#   "?ERROR ?WARN"       - Contains ERROR OR WARN
#   "-ERROR"             - NOT contains ERROR
#   '{$.level = "ERROR"}' - JSON field match

FILTER="${1:-}"
LOG_GROUP="${2:-/app/realworld-example/dev}"
LIMIT="${3:-20}"
CONTAINER="realworld-exam_localstack_1"
REGION="ap-southeast-1"

echo "Log Group: $LOG_GROUP"
echo "Filter: ${FILTER:-<none>}"
echo "Limit: $LIMIT"
echo ""

if [ -z "$FILTER" ]; then
    docker exec $CONTAINER awslocal logs filter-log-events \
      --log-group-name "$LOG_GROUP" \
      --limit $LIMIT \
      --region $REGION
else
    docker exec $CONTAINER awslocal logs filter-log-events \
      --log-group-name "$LOG_GROUP" \
      --filter-pattern "$FILTER" \
      --limit $LIMIT \
      --region $REGION
fi
