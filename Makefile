MODE ?= prod

ifeq ($(MODE),prod)
COMPOSE_FILE := server/compose.prod.yaml
UP_FLAGS := -d --build --wait
else ifeq ($(MODE),dev)
COMPOSE_FILE := server/compose.dev.yaml
UP_FLAGS := --build --watch
else
$(error MODE must be prod or dev)
endif

COMPOSE := docker compose --env-file config/postgres.env -f $(COMPOSE_FILE)

.PHONY: up down

up:
	$(COMPOSE) up $(UP_FLAGS)

down:
	$(COMPOSE) down
