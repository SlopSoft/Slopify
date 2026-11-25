# Сервер Slopify

## Запуск сервера

Выполняйте команды из корня проекта. При первом запуске создайте `config/postgres.env` с переменной `POSTGRES_PASSWORD`.

```sh
mkdir -p config
(password=$(openssl rand -hex 32) && umask 077 && set -C && printf 'POSTGRES_PASSWORD=%s\n' "$password" > config/postgres.env)
```

Соберите и запустите API и PostgreSQL, передав этот файл Docker Compose. Сервер применяет миграции при запуске.

```sh
docker compose --env-file config/postgres.env -f server/compose.yaml up -d --build --wait
curl --fail --retry 5 --retry-connrefused http://127.0.0.1:8080/health
```

API доступен на `127.0.0.1:8080`. Запрос `GET /health` возвращает `200`, если база отвечает, и `503`, если база недоступна. Для другого локального порта задайте `SLOPIFY_PORT`.

PostgreSQL устанавливает пароль при первой инициализации базы. Для уже созданной базы смените пароль роли в PostgreSQL и обновите файл. Порт PostgreSQL доступен внутри сети Compose, а данные хранятся в томе `postgres_data`.

```sh
docker compose --env-file config/postgres.env -f server/compose.yaml exec postgres psql -U postgres -d slopify
docker compose --env-file config/postgres.env -f server/compose.yaml down
```

`docker compose down` останавливает сервисы и сохраняет том.
