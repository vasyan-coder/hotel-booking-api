# Common

Библиотека общих HTTP-компонентов для Booking и Hotel Service: `ErrorDTO`, обработка ошибок, фильтр `X-Trace-Id` и проверка JWT. Собственной БД и процесса нет. Подключение MVC-компонентов — через `@Import(CommonWebConfiguration.class)`.

JWT: HS256, issuer `hotel-booking`, audience `hotel-api`, роли в массиве `roles`; секрет не короче 32 байт. `JwtSupport` предоставляет encoder, decoder и конвертер ролей. Секрет поступает из окружения.

Gateway использует реактивный стек и реализует тот же HTTP-контракт без зависимости от servlet-библиотеки.

Проверка: `./mvnw -pl common -am test` из корня проекта.
