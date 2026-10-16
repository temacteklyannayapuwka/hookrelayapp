# HookRelay

Backend-сервис для доставки вебхуков.

## Текущие возможности

- Запуск HTTP-сервера на порту 8080.
- Проверка состояния приложения: `GET /actuator/health`.

## Запуск

Требуются JDK 17+ и Maven 3.6.3+.

```bash
mvn spring-boot:run
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
