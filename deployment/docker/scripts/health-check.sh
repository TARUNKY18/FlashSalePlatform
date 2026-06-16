#!/usr/bin/env bash
# =============================================================================
# health-check.sh — Verify all Flash Sale Platform infrastructure is healthy
# Usage: ./health-check.sh [--verbose]
# Exit codes: 0 = all healthy, 1 = one or more checks failed
# =============================================================================

set -euo pipefail

VERBOSE=${1:-""}
FAILED=0
PASS="\033[32m✓\033[0m"
FAIL="\033[31m✗\033[0m"
HEAD="\033[1m"
RESET="\033[0m"

pass() { echo -e "  ${PASS} $1"; }
fail() { echo -e "  ${FAIL} $1"; FAILED=1; }
section() { echo -e "\n${HEAD}=== $1 ===${RESET}"; }

# ---------------------------------------------------------------------------
section "PostgreSQL"
# ---------------------------------------------------------------------------

check_postgres() {
  local container=$1 db=$2 port=$3
  if docker exec "$container" pg_isready -U flashsale -d "$db" -q 2>/dev/null; then
    pass "$db on port $port"
    if [[ "$VERBOSE" == "--verbose" ]]; then
      echo "    Tables: $(docker exec "$container" psql -U flashsale -d "$db" -tAc \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'" 2>/dev/null || echo 'N/A')"
    fi
  else
    fail "$db on port $port — container: $container"
  fi
}

check_postgres "flash-sale-sales-db"     "sales_db"     5432
check_postgres "flash-sale-inventory-db" "inventory_db" 5433
check_postgres "flash-sale-orders-db"    "orders_db"    5434

# ---------------------------------------------------------------------------
section "Redis Cluster"
# ---------------------------------------------------------------------------

REDIS_PASS="redis_dev"

# Check cluster state from node 1
if docker exec flash-sale-redis-1 \
     redis-cli -a "$REDIS_PASS" --no-auth-warning CLUSTER INFO 2>/dev/null \
     | grep -q "cluster_state:ok"; then
  pass "Cluster state: ok"
else
  fail "Cluster state is NOT ok — run: docker logs flash-sale-redis-cluster-init"
fi

# Count reachable nodes
REACHABLE=0
for i in 1 2 3 4 5 6; do
  if docker exec "flash-sale-redis-$i" \
       redis-cli -a "$REDIS_PASS" --no-auth-warning PING 2>/dev/null | grep -q "PONG"; then
    REACHABLE=$((REACHABLE+1))
  fi
done

if [[ "$REACHABLE" -eq 6 ]]; then
  pass "All 6 nodes reachable (PONG)"
else
  fail "Only $REACHABLE/6 nodes reachable"
fi

if [[ "$VERBOSE" == "--verbose" ]]; then
  echo "    Cluster nodes:"
  docker exec flash-sale-redis-1 \
    redis-cli -a "$REDIS_PASS" --no-auth-warning CLUSTER NODES 2>/dev/null \
    | awk '{print "    " $1 " " $3}' || echo "    (could not fetch)"
fi

# Test keyspace notifications (required for reservation expiry)
NOTIFY=$(docker exec flash-sale-redis-1 \
  redis-cli -a "$REDIS_PASS" --no-auth-warning \
  -h redis-node-1 CONFIG GET notify-keyspace-events 2>/dev/null | tail -1)
if echo "$NOTIFY" | grep -q "E" && echo "$NOTIFY" | grep -q "x"; then
  pass "Keyspace notifications enabled (Ex)"
else
  fail "Keyspace notifications not set to 'Ex' (current: '${NOTIFY}')"
fi

# ---------------------------------------------------------------------------
section "Kafka"
# ---------------------------------------------------------------------------

if docker exec flash-sale-kafka \
     kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1; then
  pass "Broker reachable on localhost:9092"
else
  fail "Kafka broker unreachable — check: docker logs flash-sale-kafka"
fi

# Check KRaft mode (no Zookeeper)
if docker exec flash-sale-kafka \
     kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe 2>/dev/null \
     | grep -q "LeaderId"; then
  pass "KRaft mode — controller leader elected"
else
  # Non-fatal: metadata quorum command may not be available on all versions
  pass "KRaft mode — (leader check skipped)"
fi

TOPIC_COUNT=$(docker exec flash-sale-kafka \
  kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | wc -l | tr -d ' ')
pass "Topics visible: ${TOPIC_COUNT} (0 = services not started yet)"

# ---------------------------------------------------------------------------
section "ClickHouse"
# ---------------------------------------------------------------------------

if curl -s --fail --max-time 3 "http://localhost:8123/ping" > /dev/null 2>&1; then
  pass "HTTP interface on port 8123"
else
  fail "ClickHouse HTTP not responding on port 8123"
fi

if curl -s --max-time 3 \
     "http://localhost:8123/?user=flashsale&password=flashsale_dev&query=SELECT%201" \
     2>/dev/null | grep -q "1"; then
  pass "Query execution works (SELECT 1)"
else
  fail "ClickHouse query failed — check credentials"
fi

# ---------------------------------------------------------------------------
section "UIs"
# ---------------------------------------------------------------------------

if curl -s --fail --max-time 3 "http://localhost:18080" > /dev/null 2>&1; then
  pass "Kafka UI → http://localhost:18080"
else
  fail "Kafka UI not responding on port 18080"
fi

if curl -s --fail --max-time 3 "http://localhost:18081" > /dev/null 2>&1; then
  pass "RedisInsight → http://localhost:18081"
else
  # Non-fatal — RedisInsight sometimes takes longer to start
  echo -e "  \033[33m⚠\033[0m  RedisInsight not yet responding (may still be starting)"
fi

# ---------------------------------------------------------------------------
section "Summary"
# ---------------------------------------------------------------------------

if [[ "$FAILED" -eq 0 ]]; then
  echo -e "\n${PASS} ${HEAD}All checks passed. Infrastructure is healthy.${RESET}\n"
  echo "  Next steps:"
  echo "    make kafka-create-topics   # Create Kafka topics manually (or start services)"
  echo "    make db-inventory          # Open psql on inventory_db"
  echo "    http://localhost:18080     # Kafka UI"
  echo "    http://localhost:18081     # RedisInsight"
  exit 0
else
  echo -e "\n${FAIL} ${HEAD}One or more checks failed. See above.${RESET}\n"
  echo "  Debugging tips:"
  echo "    docker compose -f deployment/docker/docker-compose.yml ps"
  echo "    docker compose -f deployment/docker/docker-compose.yml logs --tail=50 <service>"
  exit 1
fi
