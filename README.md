# HookRelay

Backend-сервис для доставки вебхуков.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Проверка состояния приложения: `GET /actuator/health`.
- Приём события через `POST /api/webhooks` и одна попытка доставки JSON получателю.
- Проверка обязательных полей, таймаут подключения 3 секунды и ожидания ответа 5 секунд.
- Ответ `400` при некорректном запросе, `502` при сбое доставки или ответе получателя не из диапазона `2xx`.
- Перенаправления не выполняются. События не сохраняются, повторных попыток нет.

## Запуск

Требуются JDK 17+ и Maven 3.6.3+.

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
{"delivered":true,"httpStatus":204}
```

API не имеет аутентификации; запускать его следует в доверенной локальной среде.

Запуск тестов:

```bash
mvn test
```

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
