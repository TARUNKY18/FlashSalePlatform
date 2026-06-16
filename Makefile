# =============================================================================
# Flash Sale Platform — Makefile
# Usage: make <target>
# All targets run from the project root.
# =============================================================================

COMPOSE     = docker compose -f deployment/docker/docker-compose.yml
COMPOSE_ENV = $(COMPOSE) --env-file deployment/docker/.env

.PHONY: up down restart logs ps health clean nuke \
        kafka-topics kafka-lag redis-check redis-cluster-info \
        db-sales db-inventory db-orders

# ---------------------------------------------------------------------------
# Stack lifecycle
# ---------------------------------------------------------------------------

up: ## Start all infrastructure services (detached)
	@echo "Starting Flash Sale Platform infrastructure..."
	@cp -n deployment/docker/.env.example deployment/docker/.env 2>/dev/null || true
	$(COMPOSE_ENV) up -d
	@echo "Waiting for cluster init..."
	@sleep 5
	@$(MAKE) health

down: ## Stop all services (keep volumes)
	$(COMPOSE_ENV) down --remove-orphans

restart: down up ## Restart all services

logs: ## Tail logs for all services
	$(COMPOSE_ENV) logs -f --tail=50

ps: ## Show container status
	$(COMPOSE_ENV) ps

# ---------------------------------------------------------------------------
# Health checks
# ---------------------------------------------------------------------------

health: ## Run full health check
	@echo "\n=== PostgreSQL ==="
	@docker exec flash-sale-sales-db     pg_isready -U flashsale -d sales_db     && echo "  ✓ sales_db"     || echo "  ✗ sales_db FAILED"
	@docker exec flash-sale-inventory-db pg_isready -U flashsale -d inventory_db && echo "  ✓ inventory_db" || echo "  ✗ inventory_db FAILED"
	@docker exec flash-sale-orders-db    pg_isready -U flashsale -d orders_db    && echo "  ✓ orders_db"    || echo "  ✗ orders_db FAILED"
	@echo "\n=== Redis Cluster ==="
	@docker exec flash-sale-redis-1 redis-cli -a redis_dev --no-auth-warning -h redis-node-1 CLUSTER INFO | grep cluster_state
	@echo "\n=== Kafka ==="
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1 && echo "  ✓ Kafka broker reachable" || echo "  ✗ Kafka FAILED"
	@echo "\n=== ClickHouse ==="
	@curl -s --fail "http://localhost:8123/ping" && echo "  ✓ ClickHouse HTTP" || echo "  ✗ ClickHouse FAILED"
	@echo "\n=== UIs ==="
	@curl -s --fail "http://localhost:18080" > /dev/null && echo "  ✓ Kafka UI (http://localhost:18080)" || echo "  ✗ Kafka UI"
	@echo "\n✓ Health check complete"

# ---------------------------------------------------------------------------
# Kafka operations
# ---------------------------------------------------------------------------

kafka-topics: ## List all Kafka topics
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 --list

kafka-lag: ## Show consumer group lag for all groups
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
	  --bootstrap-server localhost:9092 --describe --all-groups

kafka-create-topics: ## Manually create all topics (services do this on startup)
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 \
	  --create --if-not-exists --topic sale-events      --partitions 8  --replication-factor 1
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 \
	  --create --if-not-exists --topic inventory-events --partitions 16 --replication-factor 1
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 \
	  --create --if-not-exists --topic order-events     --partitions 8  --replication-factor 1
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 \
	  --create --if-not-exists --topic notifications.dlq --partitions 4 --replication-factor 1
	@docker exec flash-sale-kafka /opt/kafka/bin/kafka-topics.sh \
	  --bootstrap-server localhost:9092 \
	  --create --if-not-exists --topic analytics.dlq    --partitions 4  --replication-factor 1
	@echo "Topics created. Verify at http://localhost:18080"

# ---------------------------------------------------------------------------
# Redis operations
# ---------------------------------------------------------------------------

redis-check: ## Run PING against all 6 Redis nodes
	@for port in 7001 7002 7003 7004 7005 7006; do \
	  result=$$(redis-cli -p $$port -a redis_dev --no-auth-warning PING 2>&1); \
	  echo "  Node $$port: $$result"; \
	done

redis-cluster-info: ## Show Redis Cluster INFO from node 1
	@redis-cli -p 7001 -a redis_dev --no-auth-warning CLUSTER INFO

redis-stock-watch: ## Watch stock counter for SALE_ID (usage: make redis-stock-watch SALE_ID=uuid)
	@watch -n 1 "redis-cli -p 7001 -a redis_dev --no-auth-warning -c GET 'stock:$(SALE_ID)'"

# ---------------------------------------------------------------------------
# Database shells
# ---------------------------------------------------------------------------

db-sales: ## Open psql on sales_db
	@docker exec -it flash-sale-sales-db psql -U flashsale -d sales_db

db-inventory: ## Open psql on inventory_db
	@docker exec -it flash-sale-inventory-db psql -U flashsale -d inventory_db

db-orders: ## Open psql on orders_db
	@docker exec -it flash-sale-orders-db psql -U flashsale -d orders_db

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

clean: ## Stop services and remove volumes (data destroyed)
	$(COMPOSE_ENV) down -v --remove-orphans
	@echo "All volumes removed."

nuke: clean ## Same as clean but also removes images
	$(COMPOSE_ENV) down -v --rmi all --remove-orphans
	@echo "All containers, volumes, and images removed."

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'