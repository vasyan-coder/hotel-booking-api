# API Gateway

Реактивный вход в систему на Spring Cloud Gateway. Порт по умолчанию — `8080`.
Сервисы находятся динамически через Eureka и Spring Cloud LoadBalancer.

| Входящий путь | Сервис | Доступ |
| --- | --- | --- |
| `POST /api/auth/register`, `POST /api/auth/login` | booking-service | Публичный |
| `/api/bookings/**`, остальные `/api/auth/**` | booking-service | JWT с ролью USER или ADMIN |
| `/api/users/**` | booking-service | ADMIN |
| `GET /api/hotels/**`, `GET /api/rooms/**` | hotel-service | USER или ADMIN, кроме статистики |
| `GET /api/hotels/{id}/statistics` | hotel-service | ADMIN |
| `POST`, `PUT`, `PATCH`, `DELETE` для `/api/hotels/**`, `/api/rooms/**` | hotel-service | ADMIN |
| `GET /docs/booking` | booking-service `/v3/api-docs` | JWT |
| `GET /docs/hotel` | hotel-service `/v3/api-docs` | JWT |
| `GET /actuator/health` | Сам Gateway | Публичный |

Внутренние операции `/api/rooms/allocate`, `/api/rooms/release`,
`/api/rooms/{id}/confirm-availability`, `/api/rooms/{id}/release`,
а также `/api/internal/**` и `/internal/**` закрыты на Gateway для всех ролей.
Booking вызывает Hotel по внутреннему адресу с сервисным JWT.
Gateway проверяет административные права до передачи тела запроса сервисам:
USER получает 403 при попытке изменения каталога даже с некорректным телом.
Сервисы повторно проверяют роли и контролируют владение бронированием.

JWT проверяется по подписи HS256, issuer `hotel-booking`, audience `hotel-api`, сроку
действия и наличию subject. Роли читаются из массива `roles`. Переданный клиентом
Bearer-токен сохраняется при проксировании. Сервисы самостоятельно повторяют проверку JWT.

`X-Trace-Id` сохраняется, если состоит из 1–64 латинских букв, цифр или дефисов.
Иначе создаётся UUID. Заголовок передаётся сервису и возвращается клиенту, включая
ошибки 401/403. Подставленные клиентом `X-User-Id` и `X-User-Roles` удаляются.
Логи содержат JSON-поля `traceId`, `method`, `path`, `status`, `durationMs`.

Ошибки безопасности и маршрутизации возвращаются в формате `ErrorDTO`:

```json
{
  "timestamp": "2026-01-01T00:00:00Z",
  "status": 401,
  "code": "UNAUTHORIZED",
  "message": "A valid bearer token is required",
  "path": "/api/bookings",
  "traceId": "example-trace",
  "details": {}
}
```

## Конфигурация и запуск

Команды выполняются из корня проекта. Java 17+, Maven загружается через Wrapper.

```bash
./mvnw -pl gateway -am package
java -jar gateway/target/gateway-1.0.0.jar
```

Перед запуском задайте `JWT_SECRET` — одинаковый для Booking, Hotel и Gateway секрет
длиной не менее 32 байт UTF-8. Корневая инструкция содержит команду генерации.

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `JWT_SECRET` | Обязательна | Проверка JWT |
| `SERVER_PORT` | `8080` | Порт |
| `SERVER_ADDRESS` | `127.0.0.1` | Интерфейс; в Docker задаётся `0.0.0.0` |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Адрес реестра |

Тайм-аут подключения к сервису — 2 секунды, ожидания ответа — 20 секунд.
Это оставляет Booking время для повторов выделения и последующей компенсации.
Автоматический повтор POST на Gateway не включён: идемпотентность и повторы
межсервисных операций контролирует Booking.

## Проверка

```bash
./mvnw -pl gateway -am test
```

`GatewayIntegrationTest` поднимает настоящий Gateway и локальный HTTP-сервер на
случайных портах. Проверяются маршруты, перенос JWT/traceId, блокировка внутренних
операций, ответы 401/403, истёкший JWT, неверные issuer/audience, OpenAPI-маршруты.
Eureka и внешние сервисы для этих тестов не нужны.

Использованные механизмы описаны в документации
[Spring Cloud Gateway](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/configuration.html)
и [Spring Security Resource Server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
