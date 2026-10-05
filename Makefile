.PHONY: help db sandbox-images backend frontend test stop

help:
	@echo "make db        Start PostgreSQL (docker compose)"
	@echo "make sandbox-images  Pull the Scenario Lab code sandbox images (once)"
	@echo "make backend   Start PostgreSQL, then the API on :8080"
	@echo "make frontend  Start the UI on :5173"
	@echo "make test      Run backend and frontend tests"
	@echo "make stop      Stop PostgreSQL"

db:
	docker compose up -d --wait postgres

# Scenario Lab runs user code only in these pinned images; the sandbox never pulls during a run.
sandbox-images:
	sh deploy/sandbox-images.sh

backend: db
	cd backend && ./mvnw spring-boot:run

# Installs dependencies first only if they are missing.
frontend:
	cd frontend && [ -d node_modules ] || npm install
	cd frontend && npm run dev

test:
	cd backend && ./mvnw test
	cd frontend && npm test

stop:
	docker compose down
