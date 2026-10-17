# HookRelay

Backend-сервис для доставки вебхуков.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Проверка состояния приложения: `GET /actuator/health`.
- Приём события через `POST /api/webhooks`: HTTP `202 Accepted`, `deliveryId` и заголовок `Location` для проверки результата.
- Фоновая отправка сохранённых событий по одному, с интервалом между обработками 1 секунда по умолчанию.
- Проверка обязательных полей, таймаут подключения 3 секунды и ожидания ответа 5 секунд.
- Ответ `400` при некорректном запросе. Результат фоновой отправки и окончательный сбой `FAILED` видны через GET.
- Сохранение событий, адреса получателя, статуса, HTTP-кода, ошибки и времени доставки в PostgreSQL.
- Статусы: `PENDING`, `SUCCESS`, `FAILED`. Успешный ответ содержит `deliveryId`.
- Просмотр сохранённой доставки: `GET /api/webhooks/{deliveryId}`; `404` для неизвестного ID и `400` для некорректного UUID.
- Автоматическое создание схемы через миграции Flyway.
- До трёх попыток при сетевых ошибках, HTTP `408`, `429` и `5xx`: паузы перед повторами 5 и 10 секунд.
- Счётчик попыток, время следующей отправки и результаты попыток сохраняются в PostgreSQL. Перенаправления не выполняются.
- Оставшиеся `PENDING` обрабатываются после перезапуска. Блокировка строки предотвращает одновременную обработку одной записи несколькими обработчиками.

## Запуск

Требуются JDK 17+, Maven 3.6.3+ и PostgreSQL 14+.

В PostgreSQL создайте отдельного пользователя и базу от имени администратора.
Замените пример пароля своим локальным паролем:

```sql
CREATE USER hookrelay WITH PASSWORD 'replace-with-local-password';
CREATE DATABASE hookrelay OWNER hookrelay;
```

Перед запуском задайте подключение. Linux/macOS:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/hookrelay
export DB_USER=hookrelay
export DB_PASSWORD='replace-with-local-password'
```

PowerShell:

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/hookrelay"
$env:DB_USER="hookrelay"
$env:DB_PASSWORD="replace-with-local-password"
```

`DB_PASSWORD` обязателен. Значения `DB_URL` и `DB_USER` по умолчанию соответствуют примеру.
При запуске Flyway применяет миграции к выбранной БД; используйте только отдельную базу этого приложения.
Без подключения к БД приложение не запускается и события не отправляются.

```bash
mvn spring-boot:run
```

Адрес получателя задаётся переменной окружения `WEBHOOK_TARGET_URL`.
По умолчанию: `http://localhost:9000/webhook`. На этом адресе должен работать HTTP-получатель, принимающий POST-запросы.

Linux/macOS:

```bash
WEBHOOK_TARGET_URL=https://receiver.example.com/webhook mvn spring-boot:run
```

PowerShell:

```powershell
$env:WEBHOOK_TARGET_URL="https://receiver.example.com/webhook"
mvn spring-boot:run
```

Отправка события (bash):

```bash
curl -X POST http://localhost:8080/api/webhooks \
  -H 'Content-Type: application/json' \
  -d '{"event":"order.created","payload":{"orderId":42}}'
```

API сохраняет событие и возвращает HTTP `202`, не ожидая ответа получателя:

```json
{"deliveryId":"e071bdd1-f16b-4226-af51-1a24a98161e6","status":"PENDING"}
```

API не имеет аутентификации; запускать его следует в доверенной локальной среде.

Просмотр результата (подставьте `deliveryId` из ответа отправки):

```bash
curl http://localhost:8080/api/webhooks/e071bdd1-f16b-4226-af51-1a24a98161e6
```

Ответ содержит `deliveryId`, `event`, `status`, `httpStatus`, `errorMessage`, `createdAt`, `completedAt`, `attemptCount` и `nextAttemptAt`.
Время возвращается в UTC в формате ISO 8601. У `PENDING` поля `httpStatus`, `errorMessage` и `completedAt` равны `null`.
Чтение результата не запускает повторную отправку.

Получателю отправляется JSON с теми же `event` и `payload`, а также заголовок `X-HookRelay-Delivery-Id`.
Ответ `2xx` переводит запись в `SUCCESS`.
При временной ошибке запись остаётся `PENDING` до времени `nextAttemptAt`, после чего обработчик выполняет повтор.
После третьей неудачной попытки или постоянной ошибки запись становится `FAILED`; `nextAttemptAt` обнуляется.
В `attemptCount` учитывается первая отправка. Остальные `4xx` и ответы `3xx` завершают доставку без повтора.
Результаты отдельных попыток хранятся в таблице `delivery_attempts`.
Во время обработки статус остаётся `PENDING`; итог меняется после завершения транзакции.
Блокировка строки и соединение с БД удерживаются на время ограниченного таймаутами HTTP-запроса.

При аварии после получения вебхука, но до фиксации результата и счётчика в БД, возможна дополнительная отправка после перезапуска.
Гарантии «ровно один раз» нет: получателю следует учитывать `X-HookRelay-Delivery-Id` для защиты от дублей.

Настройки обработчика через переменные окружения:

- `DELIVERY_POLL_INTERVAL_MS` — пауза между обработками в миллисекундах, по умолчанию `1000`; задавайте положительное целое число.
- `DELIVERY_WORKER_ENABLED=false` — отключает обработчик; API продолжает сохранять события в `PENDING`.

Запуск тестов (JDK и Maven, отдельная установленная БД не нужна):

```bash
mvn test
```

Тесты автоматически поднимают временный PostgreSQL. На Linux запускайте их от обычного пользователя, не от `root`.
При первом запуске Maven скачивает бинарные файлы тестового PostgreSQL.

Проверка состояния:

```bash
curl http://localhost:8080/actuator/health
```

Ответ:

```json
{"status":"UP"}
```

Сборка и запуск JAR:

```bash
mvn clean package
java -jar target/hookrelayapp-0.0.1-SNAPSHOT.jar
```
