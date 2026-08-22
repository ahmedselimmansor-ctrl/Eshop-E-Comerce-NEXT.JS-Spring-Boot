# Eshop - common tasks.
#
# Everything here runs in Docker, so the only prerequisites are Docker and Make. No local JDK,
# Maven, or Node install is required - `make dev` works on a clean machine.

.DEFAULT_GOAL := help
SHELL := /bin/bash

COMPOSE := docker compose
MVN     := ./scripts/mvnd.sh
WEB     := cd web &&

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------------ local dev --

.PHONY: dev
dev: infra ## Start infrastructure, then print how to run the apps
	@echo ""
	@echo "Infrastructure is up. Run the applications with:"
	@echo "  make api      # Spring Boot on :8080"
	@echo "  make web      # storefront on :3000"
	@echo "  make admin    # admin dashboard on :3001"
	@echo ""
	@echo "  Mailpit  http://localhost:8025   (all outbound email)"
	@echo "  Jaeger   http://localhost:16686  (traces)"
	@echo "  Swagger  http://localhost:8080/swagger-ui.html"

.PHONY: infra
infra: ## Start Postgres, Redis, Elasticsearch, Mailpit, fake-GCS, Jaeger
	$(COMPOSE) up -d
	@echo "Waiting for services to become healthy…"
	@$(COMPOSE) ps

.PHONY: infra-down
infra-down: ## Stop infrastructure, keeping data
	$(COMPOSE) down

.PHONY: infra-reset
infra-reset: ## Stop infrastructure and DELETE all local data
	$(COMPOSE) down -v

.PHONY: up
up: ## Build and run everything, including the applications
	$(COMPOSE) --profile app up -d --build

.PHONY: down
down: ## Stop everything
	$(COMPOSE) --profile app down

.PHONY: logs
logs: ## Tail logs from all services
	$(COMPOSE) --profile app logs -f

# ---------------------------------------------------------------- application --

.PHONY: api
api: ## Run the API with hot reload
	$(MVN) spring-boot:run

.PHONY: web
web: ## Run the storefront with hot reload
	$(WEB) npm run dev

.PHONY: admin
admin: ## Run the admin dashboard with hot reload
	$(WEB) npm run dev:admin

.PHONY: install
install: ## Install web dependencies
	$(WEB) npm install --no-audit --no-fund

# ---------------------------------------------------------------------- build --

.PHONY: build
build: build-backend build-web ## Build everything

.PHONY: build-backend
build-backend: ## Compile and package the API
	$(MVN) -DskipTests clean package

.PHONY: build-web
build-web: ## Build both Next.js applications
	$(WEB) npm run build

.PHONY: images
images: ## Build the container images
	docker build -f infra/docker/backend.Dockerfile -t eshop/api:local backend
	docker build -f infra/docker/web.Dockerfile --build-arg APP_NAME=storefront -t eshop/web:local web
	docker build -f infra/docker/web.Dockerfile --build-arg APP_NAME=admin -t eshop/admin:local web

# ----------------------------------------------------------------------- test --

.PHONY: test
test: test-backend test-web test-mobile ## Run every test suite

.PHONY: db-repair
db-repair: ## Realign Flyway checksums after a migration comment changed (does not re-run any SQL)
	$(MVN) flyway:repair -Dflyway.url=jdbc:postgresql://localhost:5432/eshop \
		-Dflyway.user=eshop -Dflyway.password=eshop

.PHONY: test-backend
test-backend: ## Backend tests (needs `make infra` for Postgres and Redis)
	$(MVN) test

.PHONY: test-mobile
test-mobile: ## Analyze and test the Flutter app
	./scripts/flutter.sh analyze && ./scripts/flutter.sh test

.PHONY: test-web
test-web: ## Typecheck and lint the web applications
	$(WEB) npm run typecheck && npm run lint

.PHONY: typecheck
typecheck: ## Typecheck the web workspace only
	$(WEB) npm run typecheck

# ------------------------------------------------------------------ database --

.PHONY: db-shell
db-shell: ## Open psql against the local database
	$(COMPOSE) exec postgres psql -U eshop -d eshop

.PHONY: db-migrate
db-migrate: ## Apply Flyway migrations
	$(MVN) flyway:migrate

.PHONY: db-reset
db-reset: ## Drop and recreate the local database, then re-migrate
	$(COMPOSE) exec postgres psql -U eshop -d postgres -c "DROP DATABASE IF EXISTS eshop;"
	$(COMPOSE) exec postgres psql -U eshop -d postgres -c "CREATE DATABASE eshop;"
	$(MAKE) db-migrate

.PHONY: reindex
reindex: ## Rebuild the Elasticsearch product index
	curl -fsS -X POST http://localhost:8080/api/v1/admin/search/reindex \
		-H "Authorization: Bearer $${ADMIN_TOKEN:?set ADMIN_TOKEN first}"

# ----------------------------------------------------------------- security --

.PHONY: audit
audit: ## Dependency vulnerability scan and SBOM
	$(MVN) verify -Psecurity
	$(WEB) npm audit --audit-level=high

.PHONY: scan-images
scan-images: images ## Scan the built images with Trivy
	docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
		aquasec/trivy:latest image --severity HIGH,CRITICAL --exit-code 1 eshop/api:local

# -------------------------------------------------------------------- infra --

.PHONY: tf-plan
tf-plan: ## Terraform plan (ENV=dev|staging|prod)
	cd infra/terraform/envs/$(or $(ENV),dev) && terraform init -upgrade && terraform plan

.PHONY: tf-apply
tf-apply: ## Terraform apply (ENV=dev|staging|prod)
	cd infra/terraform/envs/$(or $(ENV),dev) && terraform apply

.PHONY: k8s-diff
k8s-diff: ## Preview the rendered Kubernetes manifests (ENV=dev|staging|prod)
	kubectl kustomize infra/k8s/overlays/$(or $(ENV),dev)

.PHONY: clean
clean: ## Remove build output
	rm -rf backend/target web/apps/*/.next web/apps/*/node_modules web/node_modules
