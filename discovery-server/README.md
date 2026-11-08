# Discovery Server

Eureka Server регистрирует экземпляры Booking, Hotel и Gateway.
Сервисы используют имена `booking-service` и `hotel-service`; Gateway получает
адреса через Eureka. Реестр не регистрирует сам себя и не получает реестр у соседей.

По умолчанию реестр слушает `127.0.0.1:8761`. В Docker он слушает внутреннюю сеть;
публикация порта хоста определяется корневым Compose-файлом.

| Адрес | Назначение |
| --- | --- |
| `http://localhost:8761/` | Панель Eureka |
| `http://localhost:8761/eureka/apps` | Зарегистрированные приложения, XML или JSON по Accept |
| `http://localhost:8761/actuator/health` | Состояние сервера |

## Запуск

Команды выполняются из корня проекта:

```bash
./mvnw -pl discovery-server -am package
java -jar discovery-server/target/discovery-server-1.0.0.jar
```

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `SERVER_PORT` | `8761` | Порт |
| `SERVER_ADDRESS` | `127.0.0.1` | Сетевой интерфейс |
| `EUREKA_HOSTNAME` | `localhost` | Имя узла |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | URL реестра |
| `EUREKA_SECURITY_ENABLED` | `false` | Включить HTTP Basic для Eureka |
| `EUREKA_USERNAME` | `registry` | Пользователь реестра |
| `EUREKA_PASSWORD` | Пусто | Пароль от 12 символов, обязателен при включённой защите |

Для защищённого реестра задайте `EUREKA_SECURITY_ENABLED=true`, логин и пароль, а во
всех клиентах — `EUREKA_URL=http://<username>:<password>@<registry-host>:8761/eureka/`.
Специальные символы в компонентах URL должны быть percent-encoded. Для соединения
между недоверенными сетями TLS завершается на сетевом прокси.
`/actuator/health` остаётся доступным без аутентификации. JWT бизнес-API не используется
в протоколе регистрации Eureka. Саморегистрацию и bootstrap сервисов не следует
зависимо связывать с выдачей пользовательских JWT.

## Проверка

```bash
./mvnw -pl discovery-server -am test
```

Тесты MockMvc проверяют запуск контекста, health, панель, отсутствующий маршрут,
режим HTTP Basic, правильный и неправильный пароль. Внешние сервисы и Docker
для выполнения тестов не нужны.
