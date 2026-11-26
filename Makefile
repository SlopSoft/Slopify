MODE ?= prod

ifeq ($(MODE),prod)
COMPOSE_FILES := -f server/compose.prod.yaml
UP_FLAGS := -d --build --wait
else ifeq ($(MODE),public)
COMPOSE_FILES := -f server/compose.prod.yaml -f server/compose.public.yaml
UP_FLAGS := -d --build --wait
else ifeq ($(MODE),dev)
COMPOSE_FILES := -f server/compose.dev.yaml
UP_FLAGS := --build --watch
else
$(error MODE must be prod, public or dev)
endif

COMPOSE := docker compose --env-file config/postgres.env $(COMPOSE_FILES)

.PHONY: up down

up:
	$(COMPOSE) up $(UP_FLAGS)

down:
	$(COMPOSE) down
