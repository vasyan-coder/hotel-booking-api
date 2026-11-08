# Booking Service

Сервис пользователей и бронирований. Java 17+, Spring Boot 3.5, Spring Security Resource Server, JPA/H2, Eureka Client и Resilience4j. Зависимости скачивает Maven Wrapper корневого проекта.

## Запуск

Из корня репозитория:

```bash
export JWT_SECRET='replace-with-a-random-secret-of-at-least-32-bytes'
./mvnw -pl booking-service -am package
java -jar booking-service/target/booking-service-1.0.0.jar
```

Стандартный порт — `8081`, Eureka — `http://localhost:8761/eureka/`, Hotel обнаруживается по имени `hotel-service`. Для прямого подключения без Eureka:

```bash
export HOTEL_DISCOVERY=false
export HOTEL_BASE_URL=http://localhost:8082
java -jar booking-service/target/booking-service-1.0.0.jar --eureka.client.enabled=false
```

JWT_SECRET должен совпадать с секретом Gateway и Hotel. `SEED_DATA=true` создаёт `admin / admin12345` и `user / user12345`; пароли переопределяются через `DEMO_ADMIN_PASSWORD` и `DEMO_USER_PASSWORD`. Swagger UI: `http://localhost:8081/swagger-ui/index.html`, OpenAPI: `/v3/api-docs`, health: `/actuator/health`.

## API

| Метод | Путь | Назначение и доступ |
|---|---|---|
| POST | `/api/auth/register` | Регистрация USER, токен на один час; публичный |
| POST | `/api/auth/login` | Получение токена; публичный |
| GET, POST | `/api/users` | Список, создание пользователя; ADMIN |
| GET, PATCH, DELETE | `/api/users/{id}` | Чтение, изменение пароля/роли, деактивация; ADMIN |
| POST | `/api/bookings` | Создание саги; USER, ADMIN |
| GET | `/api/bookings` | Только своя история, пагинация; USER, ADMIN |
| GET, DELETE | `/api/bookings/{uuid}` | Чтение, отмена; владелец либо ADMIN |
| GET | `/api/bookings/recommendations` | Свободные номера из Hotel, сортировка `timesBooked,id`; USER, ADMIN |

Авторизация: `Authorization: Bearer <accessToken>`. Идентификатор пользователя берётся только из проверенного JWT. Чужая бронь возвращает 404; ADMIN может читать и отменять любую бронь по UUID, но список по умолчанию содержит только его собственные бронирования.

Регистрация и вход: `{"username":"alice","password":"password123"}`. Ответ содержит `accessToken`, `tokenType: Bearer`, `expiresIn: 3600`, безопасное представление пользователя. Административное создание дополнительно принимает `role: USER|ADMIN`. PATCH принимает `password` и/или `role`. Пароли хранятся как BCrypt; поля пароля нет ни в одном ответе.

Тело создания брони:

```json
{
  "requestId": "75221059-3309-4d69-b563-897c588c0d85",
  "hotelId": 1,
  "roomId": 1,
  "autoSelect": false,
  "startDate": "2027-01-10",
  "endDate": "2027-01-12"
}
```

Даты: `today <= startDate < endDate`; конец интервала не включён. При `autoSelect: true` поле `roomId` игнорируется. Подбор и резервирование выполняются атомарно Hotel Service. Рекомендации: `/api/bookings/recommendations?hotelId=1&startDate=2027-01-10&endDate=2027-01-12&page=0&size=20`.

Параметры списков: `page` (с нуля), `size` (до 100), `sort=createdAt,desc` для бронирований. Поддерживаются поля `id,createdAt,startDate,endDate,status,hotelId,roomId`. Для пользователей: `id,username,role,active`. Неизвестная сортировка возвращает 400.

Новая подтверждённая бронь возвращает 201; повторный запрос возвращает 200 с тем же UUID и текущим состоянием. Параллельный повтор, пока первый запрос выполняет сетевой вызов, может получить 202/PENDING: состояние доступно через GET. При том же `requestId` и другом содержимом — 409. Идемпотентность действует в пределах пользователя, и повтор отменённой брони не создаёт новую.

Первичная ошибка Hotel возвращает 400/404/409 для неверных данных/отсутствующего ресурса/конфликта либо 503 при недоступности. Ошибка содержит UUID созданной CANCELLED-брони. Повтор того же запроса возвращает эту бронь; для новой попытки бронирования нужен новый `requestId`. Общий формат ошибок — ErrorDTO (`timestamp,status,code,message,path,traceId,details`).

Удаление брони означает отмену, а не физическое удаление истории. DELETE возвращает актуальный BookingView и идемпотентен. `compensationRequired: true` означает, что отмена сохранена, а освобождение номера будет повторено фоновым процессом. Удаление пользователя деактивирует учётную запись и отменяет его активные бронирования, сохраняя внешние ключи. Деактивированный пользователь не может входить или выполнять операции бронирования. Токены уже выданных ролей остаются действующими до часа в сервисах, не читающих таблицу users; изменение роли не реализует глобальное мгновенное аннулирование JWT.

## Согласованность

1. Короткая локальная транзакция блокирует строку пользователя (`PESSIMISTIC_WRITE`), проверяет `requestId`, создаёт `PENDING`, фиксирует транзакцию.
2. Вне транзакции выполняется идемпотентный `POST /api/rooms/allocate` с UUID брони.
3. Отдельная транзакция подтверждает только ещё `PENDING` запись. Уже отменённая бронь не может стать подтверждённой.
4. При ошибке фиксируются `CANCELLED` и `compensationRequired=true`, затем вызывается `POST /api/rooms/release`. Hotel хранит отмену даже для ещё не полученного allocate, поэтому запоздалый запрос не удержит номер.
5. После успешной компенсации флаг снимается. Каждые 5 секунд выполняются повторные компенсации и отмена PENDING старше 30 секунд.

Сетевые вызовы не удерживают локальные транзакции или JPA-блокировки. Retry выполняет максимум три попытки с задержками 100 и 200 мс только для транспортных ошибок, HTTP 429 и 5xx. Connect timeout — 1 с, read timeout — 2 с. CircuitBreaker использует окно 10 вызовов, минимум 5, порог ошибок 50%, открывается на 10 с; для освобождения используется отдельный circuit. Вызовы Hotel подписаны техническим JWT с ролью INTERNAL, выдача которого отсутствует в публичном API. Рекомендации передают исходный JWT пользователя.

`X-Trace-Id` передаётся между сервисами и возвращается клиенту; структурированные JSON-логи содержат `bookingId`, смену состояния и результат компенсации. Фоновое восстановление использует UUID бронирования для корреляции.

## Схема

`users(id,username,password,role,active)` — уникальный username; физическое удаление не используется.

`bookings(id UUID,user_id FK,request_id UUID,hotel_id,requested_room_id,room_id,auto_select,start_date,end_date,status,created_at,updated_at,compensation_required,failure_code,version)`.

Ограничение `UNIQUE(user_id,request_id)` страхует идемпотентность на уровне БД. Индексы: `(user_id,created_at)`, `(status,created_at)`, `compensation_required`. `requested_room_id` хранит исходный явный выбор отдельно от выделенного `room_id`, чтобы корректно сравнивать повторные запросы. Версия предотвращает потерю конкурентных изменений.

H2 работает в памяти по требованию задания. Флаг компенсации переживает сбои вызовов и повторные запуски фонового обработчика, но все данные теряются при завершении процесса. Для восстановления после перезапуска сервиса потребуются постоянная БД и миграции; данная конфигурация не обещает такую устойчивость.

## Проверка

```bash
./mvnw -pl booking-service -am test
```

`BookingIntegrationTest` поднимает реальный Spring Security/JPA/H2 с MockMvc и заменяет только внешнюю границу Hotel: вход/регистрация, 401/403, владение, даты, пагинация, подтверждение/отмена/компенсация, повторная доставка, 20 параллельных повторов одного запроса, отмена во время allocate, восстановление PENDING, удаление пользователя.

`RestHotelClientTest` использует настоящий локальный HTTP-сервер на случайном порту: transient retry, отсутствие повторов 409, тайм-ауты, отдельный circuit для компенсации, передача traceId. Тесты не требуют Docker, работающей Eureka или внешнего Hotel.
