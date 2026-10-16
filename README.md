# HookRelay

Backend-сервис для доставки вебхуков.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Проверка состояния приложения: `GET /actuator/health`.
- Приём события через `POST /api/webhooks` и одна попытка доставки JSON получателю.
- Проверка обязательных полей, таймаут подключения 3 секунды и ожидания ответа 5 секунд.
- Ответ `400` при некорректном запросе, `502` при сбое доставки или ответе получателя не из диапазона `2xx`.
- Сохранение событий, адреса получателя, статуса, HTTP-кода, ошибки и времени доставки в PostgreSQL.
- Статусы: `PENDING`, `SUCCESS`, `FAILED`. Успешный ответ содержит `deliveryId`.
- Просмотр сохранённой доставки: `GET /api/webhooks/{deliveryId}`; `404` для неизвестного ID и `400` для некорректного UUID.
- Автоматическое создание схемы через миграции Flyway.
- Перенаправления не выполняются, повторных попыток нет.

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

Получателю отправляется тот же JSON. Если он ответил `204`, API вернёт HTTP `200`:

```json
{"deliveryId":"e071bdd1-f16b-4226-af51-1a24a98161e6","delivered":true,"httpStatus":204}
```

API не имеет аутентификации; запускать его следует в доверенной локальной среде.

Просмотр результата (подставьте `deliveryId` из ответа отправки):

```bash
curl http://localhost:8080/api/webhooks/e071bdd1-f16b-4226-af51-1a24a98161e6
```

Ответ содержит `deliveryId`, `event`, `status`, `httpStatus`, `errorMessage`, `createdAt` и `completedAt`.
Время возвращается в UTC в формате ISO 8601. У `PENDING` поля `httpStatus`, `errorMessage` и `completedAt` равны `null`.
Чтение результата не запускает повторную отправку.

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
