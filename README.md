# BC-Manager

Веб-приложение для управления коммерческой недвижимостью бизнес-центра: реестр площадей,
арендаторы, умный подбор помещений, договоры аренды, график платежей и пени, заявки на
техническое обслуживание и аналитика загруженности/доходности.

## Структура монорепозитория

```text
bc-manager/
├── docs/                 # Спецификация, отчёты
├── database/             # schema.sql (DDL) и seed.sql (демо-данные)
├── shared-fixtures/      # Единый набор данных и эталонные результаты для Python и Java
├── python-core/          # Процедурное ядро на Python (без пользовательских классов)
└── backend-java/         # Spring Boot 3 + JPA + JWT + статический фронтенд
```

## Статус по сессиям

| Сессия | Тема | Статус |
| :--- | :--- | :--- |
| 1 | Инициализация репозитория, СУБД и общих фикстур | ✅ выполнено |
| 2 | Процедурная реализация алгоритмов на Python | ✅ выполнено |
| 3 | Тестирование процедурного ядра Python | ✅ выполнено |
| 4 | Каркас Java-бэкенда, ООП-домен, слой доступа к данным | ✅ выполнено |
| 5 | ООП-реализация алгоритмов и сервисов на Java | ✅ выполнено |
| 6 | JWT, DTO и REST API контроллеры | ✅ выполнено |
| 7 | Веб-интерфейс — часть 1 | ✅ выполнено |
| 8 | Веб-интерфейс — часть 2 | ✅ выполнено |
| 9 | Комплексное тестирование и финализация | ✅ выполнено |

## База данных

Требуется PostgreSQL 15+. Два равнозначных способа подготовки БД.

**Вариант 1 — локальный PostgreSQL:**

```bash
createdb bc_manager
psql -d bc_manager -f database/schema.sql
psql -d bc_manager -f database/seed.sql
```

**Вариант 2 — Docker (без установки PostgreSQL):**

```bash
docker run -d --name bc-manager-pg \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=bc_manager \
  -p 5433:5432 postgres:15-alpine

# schema.sql/seed.sql содержат DROP/TRUNCATE, поэтому для чистой БД
# достаточно залить оба файла напрямую:
docker exec -i bc-manager-pg psql -U postgres -d bc_manager < database/schema.sql
docker exec -i bc-manager-pg psql -U postgres -d bc_manager < database/seed.sql

# Остановить и удалить контейнер:
docker rm -f bc-manager-pg
```

> При запуске приложения на этом контейнере задайте
> `DB_URL=jdbc:postgresql://localhost:5433/bc_manager` (порт `5433`, не `5432`).
> Альтернатива — не заливать SQL вручную: Flyway из Spring Boot применит
> `V1__init_schema.sql` и `V2__seed_data.sql` автоматически, но тогда БД должна
> быть пустой (без уже применённых таблиц).

Демо-пользователи:

| Логин | Пароль | Роль |
| :--- | :--- | :--- |
| `admin` | `admin123` | ADMIN |
| `manager` | `manager123` | MANAGER |

> Пароли в `seed.sql` записаны BCrypt-хэшами; приведённые значения соответствуют этим хэшам.

## Python-ядро

```bash
cd python-core
python -m venv .venv
.venv\Scripts\activate     # Windows
pip install -r requirements.txt
python -m src.cli run --dataset ../shared-fixtures/test_dataset.json
```

## Java-бэкенд

```bash
cd backend-java
mvn test-compile      # компиляция домена и репозиториев
mvn test              # unit + slice + интеграционные тесты (H2, профиль test)
mvn spring-boot:run   # запуск с PostgreSQL (нужны DB_URL/DB_USER/DB_PASSWORD)
```

Переменные окружения: `DB_URL` (по умолчанию `jdbc:postgresql://localhost:5432/bc_manager`),
`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `SERVER_PORT`. Миграции Flyway `V1__init_schema.sql` и
`V2__seed_data.sql` повторяют `database/schema.sql` и `database/seed.sql`.

Пример запуска на Docker-БД (PowerShell):

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5433/bc_manager'
$env:DB_USER='postgres'; $env:DB_PASSWORD='postgres'
mvn spring-boot:run
```

Если `schema.sql`/`seed.sql` уже залиты вручную, Flyway увидит непустую схему без своей
таблицы `flyway_schema_history` и остановится. Варианты: (а) залить только через Flyway —
оставить БД пустой; (б) залить SQL вручную и запустить приложение с
`SPRING_FLYWAY_ENABLED=false`.

Структура домена (`ru.bcmanager.domain`):

- `contracts/` — интерфейсы `RentCalculable`, `Prioritizable`, `Exportable`;
- `premises/` — абстрактный `@Entity Premises` (`SINGLE_TABLE`) и наследники
  `OfficeSpace`, `RetailSpace`, `ConferenceRoom`, `ParkingSpot` с полиморфным расчётом ставки;
- `enums/` — `Role`, `PremisesType`, `PremisesStatus`, `ContractStatus`, `PaymentStatus`,
  `MaintenanceCategory`, `MaintenancePriority`, `MaintenanceStatus`;
- сущности `User`, `Tenant`, `LeaseContract`, `Payment`, `MaintenanceRequest`;
- `repository/` — Spring Data JPA репозитории всех сущностей.

Сервисы (`ru.bcmanager.service`, Сессия 5):

- `LeasePricingService` — расчёт аренды, скидки, НДС, график платежей (Алгоритм 3);
- `SpaceMatchingService` — проверка конфликтов и умный подбор (Алгоритмы 1 и 2);
- `BillingService` — пени, задолженность, фиксация оплат (Алгоритм 4);
- `MaintenanceQueueService` — приоритетная очередь заявок с SLA (Алгоритм 5);
- `AnalyticsService` — KPI, загруженность, CSV-экспорт (Алгоритм 6);
- `PremisesService`, `TenantService`, `ContractService` — CRUD и оформление договоров.

Тесты: `ParityTest` (сверка всех 6 алгоритмов с `expected_results.json`), `ServiceUnitTest`
(границы скидок, пени, SLA, конфликтов, пустая аналитика), `PremisesDomainTest`, slice-тесты JPA,
`RestApiIntegrationTest` (19 MockMvc-сценариев по разделам 5 и 8.3),
`EndToEndScenarioTest` (4 сквозных сценария Сессии 9: полный жизненный цикл договора,
жизненный цикл заявки с SLA-эскалацией, разделение ролей MANAGER/ADMIN и сохранение пени).
Ручной бенчмарк Java: `mvn test -Dtest=BenchmarkTest` (по умолчанию отключён `@Disabled`).

## Веб-интерфейс

Статика отдаётся Spring Boot из `backend-java/src/main/resources/static/` (отдельный
фронтенд-рантайм не нужен): после `mvn spring-boot:run` приложение открывается по
`http://localhost:8080/`. Swagger UI — `/swagger-ui.html`.

```text
static/
├── index.html          # каркас: боковая навигация, шапка, область контента, модалки, toast
├── css/styles.css      # дизайн-токены, карточки KPI, таблицы, бейджи, адаптивность
└── js/
    ├── api.js          # обёртка fetch: JWT, единый формат ошибок, 401, скачивание CSV
    ├── ui.js           # экранирование, форматирование, модалки, toast
    ├── app.js          # hash-роутинг, проверка роли ADMIN/MANAGER, переход с контекстом
    └── views/          # login.js, dashboard.js, premises.js, tenants.js,
                        # contracts.js, payments.js, maintenance.js
```

Роутинг: `#/dashboard`, `#/premises`, `#/tenants`, `#/contracts`, `#/payments`, `#/maintenance`.
Роль `MANAGER` не видит кнопок изменения реестра помещений; сервер независимо возвращает `403`
на `POST/PUT/DELETE /api/v1/premises`.
Chart.js подключается с CDN — без сети KPI-карточки и таблицы работают, графики пропускаются.

## Результаты финального тестирования (Сессия 9)

| Проверка | Результат |
| :--- | :--- |
| `pytest` (python-core) | ✅ 168 passed |
| `mvn -o test` (backend-java) | ✅ 51 passed, 0 failures, 1 skipped (`BenchmarkTest`, `@Disabled`) |
| `node --check` по 10 JS-модулям | ✅ 10/10 |
| Live E2E на PostgreSQL 15 (Docker, порт 5433) | ✅ 18/18 шагов |

Live-смоук проверяет: вход `admin`/`manager` и `/auth/me`, аналитику, реестр помещений,
умный подбор, предпросмотр договора, реестр платежей и пересчёт пени, приоритетную очередь
заявок с SLA, `403` для `MANAGER` на удаление помещения, CRUD помещения от `ADMIN`,
CSV-экспорт с BOM, жизненный цикл заявки и `409 Conflict` при пересечении дат аренды.

Исправлено в Сессии 9:

- хэши паролей в `seed.sql` / `V2__seed_data.sql` не соответствовали `admin123`/`manager123` —
  заменены на корректные BCrypt-хэши (cost 10);
- `GET /payments` и `GET /maintenance` отдавали `500` на реальной БД из-за
  `LazyInitializationException` (`open-in-view: false`): DTO теперь собираются внутри
  транзакции (`RegistryReadService`, `MaintenanceService.createResponse/changeStatusResponse`).

Сквозной бизнес-процесс (Сессия 8):

1. «Умный подбор» находит помещение по критериям и передаёт помещение и даты в мастер договора
   (событие `bc:route-context`, без потери контекста при смене hash).
2. Мастер договора вызывает `POST /contracts/preview`: показывает применённую скидку, надбавку
   типа, OpEx, НДС и предпросмотр графика; `409 Conflict` от сервера выводится в мастере.
3. После оформления график появляется в «Платежах», где фиксируется полная или частичная оплата
   и выполняется пересчёт пени на произвольную дату (Алгоритм 4).
4. Заявки на ТО показываются приоритетной очередью с бейджем SLA-эскалации; статус меняется
   одной кнопкой («В работу» → «Решено»).
5. Печатная форма договора открывается в отдельном окне и сразу вызывает `window.print()`.

## Общие фикстуры

- `shared-fixtures/test_dataset.json` — входные данные (пользователи, помещения, арендаторы,
  договоры, платежи, заявки).
- `shared-fixtures/expected_results.json` — вручную верифицированные результаты всех 6
  алгоритмов; используется для parity-проверки Python ↔ Java.

Все суммы — строки с фиксированной точностью (2 знака), все даты — ISO-8601.
