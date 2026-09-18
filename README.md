# Production System

Учётная система производства: заказы клиентов, производственные заказы,
техпроцессы, рабочие места, классификаторы и серийный выпуск готовой
продукции. Spring Boot 3.4 + MySQL + Flyway, веб-интерфейс на Thymeleaf.

## Требования

- **JDK 21** — обязательная версия, на 17 проект не соберётся.
- **MySQL 8** на `localhost:3306`.
- Maven не нужен: в репозитории есть обёртка `mvnw` / `mvnw.cmd`,
  которая сама скачает Maven 3.9.9 при первом запуске.

## Подготовка базы данных

Сервер MySQL должен быть запущен. Сама схема `production_system`
создаётся автоматически миграциями Flyway (`src/main/resources/db/migration`,
V1–V22) — вручную таблицы создавать не нужно.

```sql
CREATE DATABASE production_system
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

## Запуск

Локальная БД с параметрами по умолчанию:

```bash
./mvnw spring-boot:run          # Linux / macOS
mvnw.cmd spring-boot:run        # Windows
```

Приложение поднимется на http://localhost:8080

Сборка артефакта:

```bash
./mvnw -DskipTests clean package
```

## Переменные окружения

Все параметры `application.yml` имеют значения по умолчанию для локальной
разработки, в продакшене их необходимо переопределить:

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/production_system?...` | JDBC-строка подключения |
| `DB_USERNAME` | `root` | Пользователь БД |
| `DB_PASSWORD` | `12345` | Пароль БД |
| `SERVER_PORT` | `8080` | Порт HTTP |
| `PASSWORD_ENCRYPTION_KEY` | `local-development-key` | Ключ шифрования хранимых паролей |
| `INITIAL_ADMIN_USERNAME` | `admin` | Логин создаваемого администратора |
| `INITIAL_ADMIN_PASSWORD` | `ChangeMe123!` | Его пароль |

Пример запуска с переопределением (Windows PowerShell):

```powershell
$env:DB_URL = "jdbc:mysql://db-host:3306/production_system?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
$env:DB_USERNAME = "production_app"
$env:DB_PASSWORD = "<пароль>"
$env:PASSWORD_ENCRYPTION_KEY = "<32-байтный ключ в base64>"
.\mvnw.cmd spring-boot:run
```

Значение `PASSWORD_ENCRYPTION_KEY` должно быть постоянным: при его смене
ранее сохранённые зашифрованные пароли станут нерасшифровываемыми.
Нигде не фиксируйте его в репозитории.

## Доступ по локальной сети

Чтобы открывать систему с других компьютеров в той же сети.

### 1. Разрешить подключения на машине с приложением

Spring Boot по умолчанию слушает все интерфейсы (`0.0.0.0`), отдельно
настраивать это не нужно. Убедитесь, что в `application.yml` (или через
`SERVER_ADDRESS`) не прописан `127.0.0.1` — в этом случае приложение
принимает только локальные обращения.

### 2. Открыть порт в брандмауэре Windows

Выполнить в командной строке **от имени администратора**:

```bat
netsh advfirewall firewall add rule name="Production System 8080" ^
  dir=in action=allow protocol=TCP localport=8080
```

Без этого шага другие машины порт не увидят.

### 3. Узнать IP машины с приложением

```bat
ipconfig
```

Нужен адрес `IPv4` вида `192.168.1.50` (не `127.0.0.1`).

### 4. Открыть систему с другого компьютера

```
http://192.168.1.50:8080
```

Приложение перезапускать не требуется — оно уже доступно из сети.

### 5. Если база данных на отдельном сервере

Правится **только на машине с приложением**. В `application.yml`
замените хост в строке подключения:

```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.1.10:3306/production_system?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
    username: production_app
    password: <пароль>
```

Либо, не меняя файл, задайте переменные окружения:

```powershell
$env:DB_URL = "jdbc:mysql://192.168.1.10:3306/production_system?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
$env:DB_USERNAME = "production_app"
$env:DB_PASSWORD = "<пароль>"
```

После этого **обязательно перезапустите приложение**:

```powershell
.\mvnw.cmd spring-boot:run
```

### Частые проблемы

- **`Communications link failure`** — MySQL недоступен: не совпадает IP,
  БД не запущена, порт 3306 закрыт в брандмауэре, либо у пользователя
  нет прав на подключение не с `localhost` (в MySQL пользователь привязан
  к хосту, нужен `production_app`@`%` или конкретный IP).
- **`localhost` в `DB_URL` не работает с другого ПК** — `localhost` всегда
  означает ту машину, где запущено само приложение, а не компьютер
  пользователя. Указывайте реальный IP сервера БД.
- **Страница не открывается, хотя на сервере всё поднято** — закрыт порт
  8080 в брандмауэре (шаг 2).
- **IP меняется после перезагрузки** — закрепите за сервером статический
  адрес в настройках роутера или Windows.

## Первый вход

Создаётся автоматически при старте: `admin` / `ChangeMe123!`
(или значения `INITIAL_ADMIN_USERNAME` / `INITIAL_ADMIN_PASSWORD`).
Пароль требуется сменить сразу после первого входа.

## Важные особенности

- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate сверяет сущности
  со схемой и отказывается стартовать при расхождении. После правки
  сущностей всегда добавляйте миграцию Flyway с новым номером.
- Миграции неизменяемы: правка уже применённого файла `V*.sql` приведёт
  к ошибке контрольной суммы Flyway, вместо этого создавайте следующий.
- `spring.thymeleaf.cache: false` — удобно для разработки, для продакшена
  стоит включить кэширование.

## Структура

```
src/main/java/ru/company/production/
  config/       — конфигурация, инициализация данных
  controller/   — веб-контроллеры (админ, диспетчер, технология)
  dto/          — формы и представления
  entity/       — JPA-сущности
  repository/   — Spring Data репозитории и проекции
  service/      — бизнес-логика
src/main/resources/
  db/migration/ — миграции Flyway
  templates/    — Thymeleaf-страницы по ролям
  static/       — CSS, JS, изображения
```