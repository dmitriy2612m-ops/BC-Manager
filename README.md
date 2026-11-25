# BC-Manager

**BC-Manager** — полнофункциональная веб-система управления коммерческой недвижимостью бизнес-центра. Приложение объединяет реестр помещений разных типов, умный многокритериальный подбор площадей, картотеку арендаторов, оформление договоров аренды с графиком платежей и печатной формой, контроль задолженности с прогрессивной пенёй, диспетчеризацию заявок на техническое обслуживание с учётом SLA, аналитический дашборд с CSV-экспортом и личные рабочие пространства пользователей.

---

## Содержание

1. [Ключевые возможности](#ключевые-возможности)
2. [Зачем в проекте два языка: роль Python и роль Java](#зачем-в-проекте-два-языка-роль-python-и-роль-java)
3. [Технологический стек](#технологический-стек)
4. [Структура монорепозитория](#структура-монорепозитория)
5. [Бизнес-алгоритмы и математическая модель](#бизнес-алгоритмы-и-математическая-модель)
6. [База данных, безопасность и рабочие пространства](#база-данных-безопасность-и-рабочие-пространства)
7. [Веб-интерфейс и REST API](#веб-интерфейс-и-rest-api)
8. [Запуск проекта](#запуск-проекта)
9. [Тестирование и проверка качества](#тестирование-и-проверка-качества)

---

## Ключевые возможности

- **Реестр помещений 4 типов**: офисы (`OFFICE`), торговые площади (`RETAIL`), переговорные/конференц-залы (`CONFERENCE`) и парковочные места (`PARKING`) с индивидуальными коэффициентами ставки, надбавками за комплектацию и эксплуатационным сбором (OpEx).
- **Умный подбор площадей**: проверка пересечений сроков аренды, фильтрация по типу, площади и бюджету с НДС, ранжирование кандидатов по индексу релевантности `Score` (0–100) и бесшовный переход к созданию договора.
- **Контрагенты и договоры аренды**: учёт арендаторов с проверкой ИНН (10 или 12 цифр), предпросмотр стоимости со скидкой за срок и НДС 20%, автоматическая генерация графика ежемесячных начислений, синхронизация статусов помещения (`AVAILABLE ↔ RENTED`) и печатная форма договора.
- **Биллинг и пени**: учёт полных (`PAID`) и частичных (`PARTIAL`) оплат, пересчёт просрочки и прогрессивной пени (`0.1%` / `0.2%` в день, потолок `25%`) на любую контрольную дату.
- **Заявки на ТО (Service Desk)**: приоритетная очередь на базе двоичной min-кучи с автоматической SLA-эскалацией заявок `HIGH` (> 24 ч) и `MEDIUM` (> 48 ч).
- **Аналитика и экспорт**: расчёт общей и детальной загруженности (по типам и этажам), `MRR`, доходности на 1 м², истекающих в ближайшие 30 дней договоров, просрочек и критических инцидентов; выгрузка договоров и помещений в CSV (UTF-8 с BOM для Excel).
- **Персональные аккаунты и личный кабинет**: самостоятельная регистрация по ФИО, логину и паролю с созданием изолированного рабочего пространства (`owner_id`), а также смена ФИО и пароля с отзывом старых JWT-токенов.

---

## Зачем в проекте два языка: роль Python и роль Java

Архитектура проекта построена вокруг **концепции паритета (Parity)**: все 6 ключевых бизнес-алгоритмов реализованы дважды — в процедурном стиле на Python и в объектно-ориентированном стиле на Java — поверх единого контракта входных и эталонных данных в [`shared-fixtures/`](shared-fixtures/).

### Зачем нужен Python (`python-core`)

`python-core` — это **процедурное алгоритмическое ядро (эталонная модель / reference implementation)**, написанное **строго без единого пользовательского класса**: только чистые функции, словари, списки, кортежи, множества, точная десятичная арифметика `decimal.Decimal` (`ROUND_HALF_UP`) и стандартная библиотека (`heapq`, `datetime`, `calendar`, `argparse`, `json`).

Задачи Python-модуля:
1. **Независимая математическая верификация**: формулы расчёта аренды, скидок, НДС, пени, SLA-эскалации, конфликтов дат и аналитики сначала формализованы и выверены в изолированном виде без ORM, HTTP и фреймворков.
2. **Эталон для Java-бэкенда**: CLI-команда `verify` и набор тестов проверяют соответствие алгоритмов эталону `expected_results.json`, после чего Java-тест `ParityTest` сверяет ООП-реализацию с теми же результатами с точностью до копейки.
3. **Сравнительный анализ парадигм и бенчмаркинг**: позволяет на одном и том же синтетическом наборе данных (`N = 1000` и `N = 10000`) сравнить скорость, читаемость и выразительность процедурного кода на Python и ООП-реализации на Java.

### Зачем нужна Java (`backend-java`)

`backend-java` — это **промышленный серверный бэкенд на Spring Boot 3**, который превращает алгоритмическое ядро в полноценное многопользовательское веб-приложение:

1. **Объектно-ориентированная доменная модель и полиморфизм**: иерархия помещений `Premises` (`SINGLE_TABLE`) с наследниками `OfficeSpace`, `RetailSpace`, `ConferenceRoom`, `ParkingSpot` и интерфейсами `RentCalculable`, `Prioritizable`, `Exportable` устраняет условные конструкции `if/switch` в расчётных сервисах.
2. **Транзакционная персистентность**: работа с PostgreSQL 15+ через Spring Data JPA / Hibernate (`ddl-auto: validate`, `open-in-view: false`), версионирование схемы через Flyway (`V1`–`V4`) и оптимизированные `JOIN FETCH`-запросы.
3. **Безопасность и мультитенантность**: Spring Security со stateless-аутентификацией на JWT (JJWT, HMAC-SHA256), BCrypt-хэширование паролей, ролевая модель (`ADMIN` / `MANAGER`), отзыв старых токенов при смене пароля и изоляция данных персональных рабочих пространств (`WorkspaceContextService`).
4. **REST API и раздача фронтенда**: контроллеры `/api/v1/*` с Bean Validation, единый формат ошибок `ApiError` через `@RestControllerAdvice` (`GlobalExceptionHandler`), опциональный Swagger/OpenAPI и встроенная раздача SPA-клиента.

### Сравнение реализаций и замеры производительности

| Критерий | Python (`python-core`) | Java (`backend-java`) |
| :--- | :--- | :--- |
| Парадигма | Строго процедурная (без классов) | Объектно-ориентированная (полиморфизм, интерфейсы, сервисы) |
| Модели данных | `dict`, `list`, `tuple` | JPA `@Entity`, Java `record` (DTO и результаты) |
| Точная арифметика | `decimal.Decimal` (`ROUND_HALF_UP`) | `java.math.BigDecimal` (`RoundingMode.HALF_UP`) |
| Очередь с приоритетом | `heapq` (функциональная min-куча) | `java.util.PriorityQueue` + `Prioritizable` |
| Группировки и порядок | `dict` + `sorted(...)` | `HashMap`, `LinkedHashMap`, `TreeMap` |
| Назначение | Эталон расчётов, CLI (`run`, `verify`, `bench`), тесты | Production API, БД, безопасность, веб-портал |

**Сравнение скорости на синтетических наборах (минимум из 3 прогонов, из [`docs/parity-report.md`](docs/parity-report.md)):**

| Алгоритм | Python (`N = 1000`) | Java (`N = 1000`) | Python (`N = 10000`) | Java (`N = 10000`) |
| :--- | ---: | ---: | ---: | ---: |
| 1. Проверка конфликтов | 0.017 мс | 0.085 мс | 0.233 мс | 0.462 мс |
| 2. Подбор и ранжирование | 0.151 мс | 0.260 мс | 2.152 мс | 1.722 мс |
| 3. Расчёт аренды и график | 0.042 мс | 0.064 мс | 0.055 мс | 0.073 мс |
| 4. Пени и задолженность | 3.417 мс | 1.232 мс | 40.003 мс | 6.225 мс |
| 5. Очередь заявок (куча) | 0.024 мс | 0.169 мс | 0.198 мс | 0.506 мс |
| 6. Аналитика KPI | 1.753 мс | 0.794 мс | 19.640 мс | 6.220 мс |

Обе реализации демонстрируют рост времени, близкий к линейному (или `O(N log N)` для сортировки/кучи), и выдают **0 расхождений** на контрольных фикстурах.

---

## Технологический стек

| Слой | Технологии и версии |
| :--- | :--- |
| **Java Backend** | Java 17+, Spring Boot 3.3.5 (`Web`, `Data JPA`, `Security`, `Validation`), Hibernate ORM, Flyway (`flyway-core`, `flyway-database-postgresql`), JJWT 0.12.6, Springdoc OpenAPI 2.6.0, Apache Maven |
| **Python Core** | Python 3.11+, стандартная библиотека (`decimal`, `heapq`, `datetime`, `calendar`, `argparse`, `json`), `pytest >= 8.0`, `ruff >= 0.4` |
| **База данных** | PostgreSQL 15+ (продакшен и Docker), H2 Database в режиме совместимости с PostgreSQL (автотесты) |
| **Frontend** | Нативный JavaScript (ES6 Modules, SPA без сборщиков и фреймворков), HTML5, CSS3 (токены, WCAG 2.2 AA, `tabular-nums`, адаптивная вёрстка), Chart.js 4.4.1 (CDN) |
| **Контейнеризация** | Docker (multi-stage сборка: `maven:3.9.9-eclipse-temurin-17-alpine` → `eclipse-temurin:17-jre-alpine`), Docker Compose |
| **Тестирование** | JUnit 5, AssertJ, Mockito 5.14.2 (`subclass` mock maker), Spring MockMvc, `spring-security-test`, `pytest` |

---

## Структура монорепозитория

```text
BC-Manager/
├── .env.example                            # Шаблон переменных окружения для Docker Compose
├── docker-compose.yml                      # Стек PostgreSQL 15 + Spring Boot приложение
├── database/
│   ├── schema.sql                          # Эталонная DDL-схема БД (PostgreSQL 15+, 3НФ)
│   └── seed.sql                            # Демонстрационные данные (15 помещений, 6 арендаторов, 8 договоров, заявки)
├── shared-fixtures/
│   ├── test_dataset.json                   # Единый входной набор данных для Python и Java
│   └── expected_results.json               # Эталонные результаты 6 алгоритмов для parity-проверок
├── docs/
│   ├── api-spec.md                         # Примеры запросов и ответов REST API
│   └── parity-report.md                    # Отчёт о паритете Python ↔ Java и замеры производительности
├── python-core/
│   ├── pyproject.toml                      # Настройки pytest и ruff
│   ├── requirements.txt                    # Зависимости (pytest, ruff)
│   ├── src/
│   │   ├── data_loader.py                  # Нормализация JSON в dict/list с Decimal и date/datetime
│   │   ├── lease_algorithms.py             # Алгоритмы 1–3 (конфликты, подбор, расчёт аренды и графика)
│   │   ├── billing_algorithms.py           # Алгоритм 4 (пени, задолженность, фиксация оплат)
│   │   ├── maintenance_queue.py            # Алгоритм 5 (приоритетная очередь на heapq + SLA)
│   │   ├── analytics.py                    # Алгоритм 6 (KPI, загруженность, MRR, CSV-строки)
│   │   └── cli.py                          # CLI-интерфейс: команды run, verify и bench
│   └── tests/                              # 6 наборов модульных и parity-тестов + conftest.py
└── backend-java/
    ├── Dockerfile                          # Multi-stage сборка JAR и JRE-образа
    ├── pom.xml                             # Конфигурация Maven и зависимостей
    └── src/
        ├── main/
        │   ├── java/ru/bcmanager/
        │   │   ├── BcManagerApplication.java
        │   │   ├── config/                 # SecurityConfig, OpenApiConfig, обработчики 401/403
        │   │   ├── security/               # JwtService, JwtAuthenticationFilter, CustomUserDetailsService
        │   │   ├── domain/
        │   │   │   ├── contracts/          # Интерфейсы RentCalculable, Prioritizable, Exportable
        │   │   │   ├── enums/              # 8 перечислений предметной области
        │   │   │   ├── premises/           # Premises (abstract) → OfficeSpace, RetailSpace, ConferenceRoom, ParkingSpot
        │   │   │   └── *.java              # User, Tenant, LeaseContract, Payment, MaintenanceRequest
        │   │   ├── repository/             # Spring Data JPA репозитории с JOIN FETCH и фильтрацией по owner_id
        │   │   ├── service/                # Алгоритмические и бизнес-сервисы + RegistryReadService + WorkspaceContextService
        │   │   ├── dto/                    # Java Records (запросы/ответы) и DtoMapper
        │   │   ├── controller/             # REST-контроллеры (/api/v1/*)
        │   │   └── exception/              # ApiError и GlobalExceptionHandler
        │   └── resources/
        │       ├── application.yml         # Конфигурация БД, Flyway, JWT, OpenAPI
        │       ├── db/migration/           # Flyway-миграции V1__init_schema.sql .. V4__user_workspaces_and_registration.sql
        │       └── static/
        │           ├── index.html          # Портал входа/регистрации и каркас SPA
        │           ├── guide.html          # Публичная памятка по расчётам аренды и пени
        │           ├── architecture.html   # Публичное руководство по работе с системой
        │           ├── assets/             # Логотип и иконки (logo.png, icon.png, favicon.ico)
        │           ├── css/styles.css      # Дизайн-система (шалфейно-бирюзовая палитра #0f766e, токены, адаптив)
        │           └── js/                 # api.js, ui.js, app.js и 8 модулей экранов в views/
        └── test/java/ru/bcmanager/         # Доменные, модульные, parity, интеграционные, E2E и benchmark-тесты
```

---

## Бизнес-алгоритмы и математическая модель

Во всех алгоритмах денежные суммы вычисляются в фиксированной десятичной арифметике (`Decimal` в Python / `BigDecimal` в Java) с банковским математическим округлением `HALF_UP` до 2 знаков после запятой (до копеек).

### Алгоритм 1: Проверка конфликтов аренды

- **Реализация**: [`lease_algorithms.py`](python-core/src/lease_algorithms.py) (`check_lease_conflict`), [`SpaceMatchingService.java`](backend-java/src/main/java/ru/bcmanager/service/SpaceMatchingService.java) (`checkLeaseConflict`).
- **Логика**: проверяет пересечение запрашиваемого включительного интервала `[startDate, endDate]` с существующими договорами помещения в статусах `ACTIVE` и `DRAFT` (договоры `COMPLETED` и `TERMINATED` игнорируются; при редактировании можно исключить сам договор через `excludeContractId`).
- **Условие пересечения**: `start_a <= end_b && start_b <= end_a`. Стык в соседние дни конфликтом не считается. При наличии нескольких пересечений детерминированно возвращается конфликт с наименьшим `start_date`.
- **Сложность**: $O(C)$ по числу договоров (или $O(C_p)$ при предварительной группировке по помещению).

### Алгоритм 2: Умный подбор и многокритериальное ранжирование помещений

- **Реализация**: [`lease_algorithms.py`](python-core/src/lease_algorithms.py) (`match_and_rank_premises`), [`SpaceMatchingService.java`](backend-java/src/main/java/ru/bcmanager/service/SpaceMatchingService.java) (`matchAndRank`).
- **Логика**:
  1. Отбираются помещения только в статусе `AVAILABLE`.
  2. Применяются фильтры по типу (`type`), диапазону площади `[minArea, maxArea]` и отсутствию конфликта по Алгоритму 1 (договоры предварительно группируются по `premises_id` в `dict` / `HashMap`).
  3. Для каждого кандидата рассчитывается месячная стоимость с НДС по Алгоритму 3 (`estimatedMonthlyCost`); превышающие `maxBudget` отсеиваются.
  4. Вычисляется индекс релевантности `Score` $\in [0, 100]$:

$$\text{Score} = \text{clamp}\left(100 - 50 \cdot \frac{|\text{area} - \text{targetArea}|}{\max(\text{targetArea}, 1)} - 50 \cdot \frac{\text{cost}}{\text{maxBudget}},\ 0,\ 100\right)$$

  5. Кандидаты сортируются детерминированно: `score DESC` $\to$ `estimatedMonthlyCost ASC` $\to$ `premisesId ASC`, после чего каждому присваивается `rank`.
- **Сложность**: $O(C + P \log P)$, где $P$ — число помещений, $C$ — число договоров.

### Алгоритм 3: Расчёт стоимости аренды и графика платежей

- **Реализация**: [`lease_algorithms.py`](python-core/src/lease_algorithms.py) (`calculate_rent_and_schedule`), [`LeasePricingService.java`](backend-java/src/main/java/ru/bcmanager/service/LeasePricingService.java) + полиморфная иерархия [`Premises`](backend-java/src/main/java/ru/bcmanager/domain/premises/Premises.java).
- **Параметры по типам помещений**:

| Тип (`PremisesType`) | Класс Java | Коэффициент типа | Надбавка за комплектацию | Эксплуатационный сбор (OpEx) |
| :--- | :--- | ---: | :--- | ---: |
| `OFFICE` | `OfficeSpace` | `1.00` | `+10%` при наличии мебели (`hasFurniture`) | `150.00 ₽/м²` |
| `RETAIL` | `RetailSpace` | `1.25` | `+15%` при наличии витрины (`hasShowcase`) | `200.00 ₽/м²` |
| `CONFERENCE` | `ConferenceRoom` | `1.15` | Нет (учитывается `capacityPeople`) | `100.00 ₽/м²` |
| `PARKING` | `ParkingSpot` | `1.00` | `+12%` для крытого места (`covered`) | `50.00 ₽/м²` |

- **Формула и порядок округления** (гарантирует тождество `net + vat == total`):
  1. `base = area × baseRatePerSqm`
  2. `typed = base × typeFactor × (1 + featureSurcharge)`
  3. `opex = area × opexPerSqm`
  4. Число полных месяцев `months` определяет скидку за длительность: `< 6` мес. $\to$ `0%`, `6..11` мес. $\to$ `5%`, $\ge 12$ мес. $\to$ `10%`.
  5. `net = round((typed + opex) × (1 − discount / 100))`
  6. `vat = round(net × 0.20)` (ставка НДС по умолчанию `20%`)
  7. `total = round(net + vat)`
- **График платежей**: создаётся `months` записей; 1-й платёж приходится на дату начала договора (`startDate`), каждый последующий — на **5-е число** соответствующего месяца (с ограничением по длине короткого месяца).
- **Сложность**: $O(1)$ для расчёта ставки и $O(M)$ для генерации графика из $M$ месяцев.

### Алгоритм 4: Расчёт пени, задолженности и обработка платежей

- **Реализация**: [`billing_algorithms.py`](python-core/src/billing_algorithms.py), [`BillingService.java`](backend-java/src/main/java/ru/bcmanager/service/BillingService.java).
- **Правила начисления пени**:
  - Участвуют неоплаченные и частично оплаченные платежи (`status != PAID`), у которых `dueDate < calculationDate`.
  - Непогашенный остаток: `debt = amountDue − paidAmount`, срок просрочки `overdueDays = calculationDate − dueDate`.
  - Ставка: **0.1% в день** за первые `30` дней просрочки и **0.2% в день** начиная с `31`-го дня.
  - Потолок ограничения (cap): максимальная пеня не может превышать **25% от остатка долга** (`debt × 0.25`).
- **Агрегация и оплаты**:
  - Агрегат по договорам формируется в детерминированном порядке по `contractId` (`TreeMap` в Java / сортировка ключей в Python), включая договоры с нулевой задолженностью.
  - Внесение оплаты переводит платёж в `PAID` (если `paidAmount >= amountDue`) либо в `PARTIAL`.
- **Сложность**: $O(N + K \log K)$, где $N$ — количество платежей, $K$ — количество договоров.

### Алгоритм 5: Приоритетная очередь заявок на ТО с SLA-эскалацией

- **Реализация**: [`maintenance_queue.py`](python-core/src/maintenance_queue.py) (`heapq`), [`MaintenanceQueueService.java`](backend-java/src/main/java/ru/bcmanager/service/MaintenanceQueueService.java) (`PriorityQueue` + интерфейс `Prioritizable`).
- **Логика**:
  - В очередь попадают только активные заявки (`OPEN` и `IN_PROGRESS`).
  - Базовые веса: `CRITICAL = 1`, `HIGH = 2`, `MEDIUM = 3`, `LOW = 4`.
  - **Правила SLA-эскалации** (при строгом превышении порога ожидания `now − createdAt`):
    - `HIGH` при ожидании $> 24$ часов $\to$ эффективный вес `1` (уровень `CRITICAL`);
    - `MEDIUM` при ожидании $> 48$ часов $\to$ эффективный вес `2` (уровень `HIGH`);
    - `CRITICAL` и `LOW` не эскалируются.
  - Ключ сортировки в двоичной min-куче: `(effectiveWeight, createdAt, id)`.
- **Сложность**: $O(Q \log Q)$ на построение и извлечение всей очереди из $Q$ заявок, $O(\log Q)$ на добавление/извлечение элемента.

### Алгоритм 6: Аналитика загруженности, доходности и CSV-экспорт

- **Реализация**: [`analytics.py`](python-core/src/analytics.py), [`AnalyticsService.java`](backend-java/src/main/java/ru/bcmanager/service/AnalyticsService.java).
- **Логика**:
  - На отчётную дату `asOf` помещение считается занятым, если по нему существует договор в статусе `ACTIVE`, покрывающий дату (`startDate <= asOf <= endDate`).
  - Считаются: общая и занятая площадь, процент заполняемости, `MRR` (сумма ежемесячной аренды действующих договоров), средняя доходность на 1 м² занятой площади, разбивка по типам помещений (в стабильном порядке `OFFICE`, `RETAIL`, `CONFERENCE`, `PARKING`) и по этажам (в порядке возрастания через `TreeMap`).
  - Дополнительно формируются: список активных договоров, истекающих в окне `30` дней (`EXPIRING_WINDOW_DAYS = 30`), число открытых критических заявок на ТО, количество и сумма просроченных платежей.
  - Экспорт в CSV использует `;` как разделитель, полиморфный метод `Exportable.toCsvRow()` на стороне Java и добавляет маркер UTF-8 BOM (`0xEF 0xBB 0xBF`) для корректного открытия кириллицы в Excel.
- **Сложность**: $O(P + C + N + Q)$.

---

## База данных, безопасность и рабочие пространства

### Схема данных (PostgreSQL 15+, 3НФ)

Схема описана в [`database/schema.sql`](database/schema.sql) и дублируется в Flyway-миграциях [`backend-java/src/main/resources/db/migration/`](backend-java/src/main/resources/db/migration/):

1. `users` — учётные записи (`ADMIN` / `MANAGER`), BCrypt-хэши паролей, флаг `personal_workspace`.
2. `premises` — таблица помещений по стратегии `SINGLE_TABLE` (дискриминатор `premises_type`), с проверками положительной площади/ставки и опциональной привязкой к `owner_id`.
3. `tenants` — арендаторы с проверкой формата ИНН (`CHECK (inn ~ '^[0-9]{10}$' OR inn ~ '^[0-9]{12}$')`).
4. `lease_contracts` — договоры аренды (`end_date > start_date`), ссылка на автора (`created_by`) и владельца пространства (`owner_id`).
5. `payments` — помесячные начисления по договору с уникальной парой `(contract_id, period_number)`.
6. `maintenance_requests` — заявки на техническое обслуживание с категориями и приоритетами.

Для поддержки мультитенантности введены частичные уникальные индексы (`WHERE owner_id IS NULL` для общего пространства и `WHERE owner_id IS NOT NULL` для персональных кабинетов) — два разных пользователя могут завести помещение с одинаковым кодом (например, `A-101`) или контрагента с одним ИНН без конфликтов.

### Безопасность и изоляция данных

- **Stateless JWT**: подпись HMAC-SHA256 (`JwtService`), срок жизни настраивается через `JWT_EXPIRATION_MINUTES` (по умолчанию 480 минут).
- **Отзыв токенов при смене пароля**: каждый токен содержит клейм `pwdVer` (отпечаток текущего BCrypt-хэша); фильтр `JwtAuthenticationFilter` сверяет его с БД, поэтому после смены пароля все старые токены немедленно отклоняются с кодом `401`.
- **Ролевое разграничение**: создание, редактирование и удаление помещений (`POST/PUT/DELETE /api/v1/premises`) разрешены только роли `ADMIN`; роль `MANAGER` работает в режиме операционного управления договорами, платежами, арендаторами и заявками.
- **Изолированные рабочие пространства (`WorkspaceContextService`)**:
  - При регистрации через `POST /api/v1/auth/register` создаётся пользователь с `personalWorkspace = true` и ролью `ADMIN`, который получает чистый пустой рабочий кабинет (`owner_id = user.id`). Его помещения, арендаторы, договоры, платежи и заявки полностью скрыты от других пользователей.
  - Штатные учётные записи из демо-набора (`admin`, `manager`) работают в общем пространстве (`owner_id IS NULL`).
- **Надёжность Hibernate и защита от XSS**: `spring.jpa.open-in-view: false` сочетается с `JOIN FETCH`-запросами и транзакционным фасадом чтения `RegistryReadService`, что исключает `LazyInitializationException`; на фронтенде весь вывод проходит через `escapeHtml` (`ui.js`).

---

## Веб-интерфейс и REST API

### Веб-интерфейс (SPA + справочный портал)

Статические файлы обслуживаются напрямую Spring Boot из [`backend-java/src/main/resources/static/`](backend-java/src/main/resources/static/) без Node.js/сборщиков в продакшене:

- **Публичные страницы**:
  - `/` (`index.html`) — обзорная страница, вход и самостоятельная регистрация;
  - `/guide.html` — памятка по расчётам ставок, скидок, НДС и пени;
  - `/architecture.html` — руководство пользователя по разделам кабинета и очередности работы.
- **Маршруты SPA** (hash-роутинг в [`app.js`](backend-java/src/main/resources/static/js/app.js)):
  - `#/dashboard` — KPI-карточки, диаграммы загруженности Chart.js по типам и этажам, список истекающих договоров и выгрузка CSV;
  - `#/premises` — реестр помещений с фильтрами и вкладкой «Умный подбор» (передаёт выбранное помещение и даты в мастер договора через событие `bc:route-context`);
  - `#/tenants` — поиск по названию/ИНН, карточка контрагента со сводкой и прямыми переходами к договорам и платежам;
  - `#/contracts` — реестр, мастер оформления с предпросмотром графика, управление статусами (`DRAFT → ACTIVE → COMPLETED/TERMINATED`) и печатная форма;
  - `#/payments` — фильтрация по договору и просрочкам, внесение оплаты и пересчёт пени на дату;
  - `#/maintenance` — переключение между приоритетной очередью (Алгоритм 5 с бейджем SLA-эскалации) и общим реестром;
  - `#/profile` — просмотр роли и режима доступа, редактирование ФИО и смена пароля.

### Основные группы REST API (`/api/v1`)

| Префикс | Контроллер | Основные операции |
| :--- | :--- | :--- |
| `/api/v1/auth` | `AuthController` | `POST /login`, `POST /register`, `GET /me`, `POST /change-password`, `PUT\|PATCH /profile`, `GET /authorities` |
| `/api/v1/premises` | `PremisesController` | `GET` (фильтры), `GET /{id}`, `POST` / `PUT /{id}` / `DELETE /{id}` (только `ADMIN`), `POST /match` (Алгоритм 2) |
| `/api/v1/tenants` | `TenantController` | `GET` (поиск по названию/ИНН), `GET /{id}` (карточка с договорами), `POST`, `PUT /{id}`, `PATCH /{id}/deactivate` |
| `/api/v1/contracts` | `ContractController` | `GET` (фильтры), `GET /{id}` (с графиком платежей), `POST /preview` (Алгоритмы 1 и 3), `POST`, `PATCH /{id}/status` |
| `/api/v1/payments` | `PaymentController` | `GET` (фильтры `contractId`, `status`, `overdueOnly`, `asOf`), `POST /{id}/pay`, `POST /recalculate-penalties` (Алгоритм 4) |
| `/api/v1/maintenance` | `MaintenanceController` | `GET` (`prioritized=true` — очередь по Алгоритму 5), `POST`, `PATCH /{id}/status` |
| `/api/v1/analytics` | `AnalyticsController` | `GET /summary` (KPI на дату `asOf`), `GET /export/csv` (договоры), `GET /export/premises/csv` (помещения) |

> Swagger UI (`/swagger-ui.html`) и OpenAPI-схема (`/v3/api-docs`) по умолчанию выключены (`SWAGGER_ENABLED=false` в `application.yml`). Для их активации в среде разработки задайте `SWAGGER_ENABLED=true`.

---

## Запуск проекта

### Способ 1. Docker Compose (рекомендуемый)

1. Скопируйте шаблон переменных окружения и при необходимости обновите секреты (длина `JWT_SECRET` должна быть **не менее 32 байт**):
   ```bash
   cp .env.example .env
   ```
2. Поднимите PostgreSQL 15 и Spring Boot приложение:
   ```bash
   docker compose up -d --build
   ```
3. Откройте в браузере `http://localhost:8080/` (или порт, указанный в `SERVER_PORT`). Flyway автоматически применит все миграции `V1`–`V4`.

На стартовой странице сразу доступна вкладка **«Регистрация»**, где можно создать собственный аккаунт с правами `ADMIN` и чистым личным пространством (либо использовать демонстрационные учётные записи `admin` / `manager` из `seed.sql` / Flyway при наличии актуальных учётных данных).

### Способ 2. Локальная разработка (Java 17+ и PostgreSQL 15+)

1. Создайте пустую базу данных `bc_manager` в PostgreSQL.
   - **Вариант А (через Flyway — по умолчанию)**: оставьте базу полностью пустой — Spring Boot при старте применит `V1`–`V4`.
   - **Вариант Б (ручная заливка SQL)**: примените [`database/schema.sql`](database/schema.sql) и [`database/seed.sql`](database/seed.sql) через `psql`, а приложение запускайте с `SPRING_FLYWAY_ENABLED=false` (иначе Flyway откажется стартовать на непустой схеме без таблицы `flyway_schema_history`).
2. Запустите бэкенд в `backend-java/`:
   ```bash
   cd backend-java
   mvn spring-boot:run
   ```
   Пример для PowerShell с переопределением подключения:
   ```powershell
   $env:DB_URL='jdbc:postgresql://localhost:5432/bc_manager'
   $env:DB_USER='postgres'
   $env:DB_PASSWORD='postgres'
   $env:JWT_SECRET='local-dev-secret-key-at-least-32-characters-long'
   mvn spring-boot:run
   ```

**Переменные окружения Spring Boot:**

| Переменная | Назначение | Значение по умолчанию (`application.yml`) |
| :--- | :--- | :--- |
| `DB_URL` | JDBC-строка подключения к PostgreSQL | `jdbc:postgresql://localhost:5432/bc_manager` |
| `DB_USER` | Пользователь БД | `postgres` |
| `DB_PASSWORD` | Пароль БД | `postgres` |
| `JWT_SECRET` | Секрет подписи JWT (мин. 32 байта) | встроенный dev-ключ (обязательно сменить в prod) |
| `JWT_EXPIRATION_MINUTES` | Срок жизни токена (мин.) | `480` |
| `SERVER_PORT` | HTTP-порт приложения | `8080` |
| `SWAGGER_ENABLED` | Включение `/swagger-ui.html` и `/v3/api-docs` | `false` |
| `SPRING_FLYWAY_ENABLED` | Автоматические миграции Flyway | `true` |

### Способ 3. Запуск процедурного ядра Python (`python-core`)

```bash
cd python-core
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt

# Прогон всех 6 алгоритмов с выводом JSON-отчёта
python -m src.cli run --dataset ../shared-fixtures/test_dataset.json

# Сверка с эталоном shared-fixtures/expected_results.json
python -m src.cli verify

# Бенчмарк на синтетических выборках (N = 1000 и 10000)
python -m src.cli bench
```

---

## Тестирование и проверка качества

### Python-ядро (`python-core`)

```bash
cd python-core
pytest                  # модульные тесты всех 6 алгоритмов, загрузчика и parity (test_parity.py)
ruff check src tests    # проверка стиля и статический анализ кода
```

### Java-бэкенд (`backend-java`)

В профиле `test` ([`application-test.yml`](backend-java/src/test/resources/application-test.yml)) используется встроенная база H2 в режиме PostgreSQL, поэтому для запуска тестов внешняя БД не требуется:

```bash
cd backend-java
mvn test
```

Состав тестовых наборов (`src/test/java/ru/bcmanager/`):
- `BcManagerApplicationTest` — проверка поднятия контекста Spring Boot;
- `PremisesDomainTest` — полиморфные ставки, OpEx, SLA-эскалация, пересечения дат, валидация;
- `ServiceUnitTest` — граничные условия алгоритмов (скидки 5/6/11/12 мес., пени и потолок 25%, пустая аналитика);
- `ParityTest` — поэлементная сверка всех 6 алгоритмов Java с [`shared-fixtures/expected_results.json`](shared-fixtures/expected_results.json);
- `RestApiIntegrationTest` — интеграционные MockMvc-сценарии REST API и ролевой безопасности;
- `UserRegistrationWorkspaceTest` — регистрация, изоляция данных между пользователями, смена пароля с отзывом старого JWT, обновление ФИО;
- `ContractCardRegressionTest` — регрессионный тест карточки договора без `@Transactional` (`open-in-view: false`);
- `EndToEndScenarioTest` — сквозные бизнес-сценарии (полный жизненный цикл договора, эскалация и закрытие заявки, права `ADMIN`/`MANAGER`, сохранение пени);
- `BenchmarkTest` — замеры производительности Java-алгоритмов (отключён по умолчанию `@Disabled`, запускается вручную после снятия аннотации через `mvn test -Dtest=BenchmarkTest`).

### Проверка синтаксиса фронтенда

```bash
node --check backend-java/src/main/resources/static/js/*.js backend-java/src/main/resources/static/js/views/*.js
```
