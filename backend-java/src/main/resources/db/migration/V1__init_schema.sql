-- =============================================================================
-- BC-Manager — начальная схема БД (Flyway V1)
-- Соответствует database/schema.sql (Plan.md, раздел 3).
-- PostgreSQL 15+
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 3.1. users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'MANAGER'))
);

-- -----------------------------------------------------------------------------
-- 3.2. premises (SINGLE_TABLE inheritance, discriminator premises_type)
-- -----------------------------------------------------------------------------
CREATE TABLE premises (
    id                 BIGSERIAL     PRIMARY KEY,
    code               VARCHAR(30)   NOT NULL UNIQUE,
    building           VARCHAR(50)   NOT NULL,
    floor              INTEGER       NOT NULL,
    premises_type      VARCHAR(20)   NOT NULL,
    area_sqm           NUMERIC(8, 2) NOT NULL,
    base_rate_per_sqm  NUMERIC(10, 2) NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    has_furniture      BOOLEAN       NOT NULL DEFAULT FALSE,
    has_showcase       BOOLEAN       NOT NULL DEFAULT FALSE,
    capacity_people    INTEGER       NULL,
    is_covered         BOOLEAN       NOT NULL DEFAULT TRUE,
    description        TEXT          NULL,
    CONSTRAINT chk_premises_type   CHECK (premises_type IN ('OFFICE', 'RETAIL', 'CONFERENCE', 'PARKING')),
    CONSTRAINT chk_premises_status CHECK (status IN ('AVAILABLE', 'RENTED', 'RESERVED', 'MAINTENANCE')),
    CONSTRAINT chk_premises_area   CHECK (area_sqm > 0),
    CONSTRAINT chk_premises_rate   CHECK (base_rate_per_sqm > 0),
    CONSTRAINT chk_premises_capacity CHECK (capacity_people IS NULL OR capacity_people > 0)
);

-- -----------------------------------------------------------------------------
-- 3.3. tenants
-- -----------------------------------------------------------------------------
CREATE TABLE tenants (
    id             BIGSERIAL    PRIMARY KEY,
    company_name   VARCHAR(150) NOT NULL,
    inn            VARCHAR(12)  NOT NULL UNIQUE,
    contact_person VARCHAR(120) NOT NULL,
    phone          VARCHAR(30)  NOT NULL,
    email          VARCHAR(120) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tenants_inn CHECK (inn ~ '^[0-9]{10}$' OR inn ~ '^[0-9]{12}$')
);

-- -----------------------------------------------------------------------------
-- 3.4. lease_contracts
-- -----------------------------------------------------------------------------
CREATE TABLE lease_contracts (
    id               BIGSERIAL     PRIMARY KEY,
    contract_number  VARCHAR(40)   NOT NULL UNIQUE,
    premises_id      BIGINT        NOT NULL REFERENCES premises(id),
    tenant_id        BIGINT        NOT NULL REFERENCES tenants(id),
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    monthly_rent     NUMERIC(12, 2) NOT NULL,
    discount_percent NUMERIC(5, 2) NOT NULL DEFAULT 0,
    deposit_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status           VARCHAR(20)   NOT NULL,
    created_by       BIGINT        NULL REFERENCES users(id),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_contract_dates  CHECK (end_date > start_date),
    CONSTRAINT chk_contract_rent   CHECK (monthly_rent >= 0),
    CONSTRAINT chk_contract_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'TERMINATED')),
    CONSTRAINT chk_contract_discount CHECK (discount_percent >= 0 AND discount_percent <= 100),
    CONSTRAINT chk_contract_deposit  CHECK (deposit_amount >= 0)
);

-- -----------------------------------------------------------------------------
-- 3.5. payments
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id             BIGSERIAL      PRIMARY KEY,
    contract_id    BIGINT         NOT NULL REFERENCES lease_contracts(id) ON DELETE CASCADE,
    period_number  INTEGER        NOT NULL,
    due_date       DATE           NOT NULL,
    amount_due     NUMERIC(12, 2) NOT NULL,
    paid_amount    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    paid_date      DATE           NULL,
    penalty_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL,
    CONSTRAINT chk_payment_amount  CHECK (amount_due > 0),
    CONSTRAINT chk_payment_paid    CHECK (paid_amount >= 0),
    CONSTRAINT chk_payment_penalty CHECK (penalty_amount >= 0),
    CONSTRAINT chk_payment_status  CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'PARTIAL')),
    CONSTRAINT uq_payment_contract_period UNIQUE (contract_id, period_number)
);

-- -----------------------------------------------------------------------------
-- 3.6. maintenance_requests
-- -----------------------------------------------------------------------------
CREATE TABLE maintenance_requests (
    id          BIGSERIAL    PRIMARY KEY,
    premises_id BIGINT       NOT NULL REFERENCES premises(id),
    tenant_id   BIGINT       NULL REFERENCES tenants(id),
    title       VARCHAR(150) NOT NULL,
    description TEXT         NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    priority    VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMP    NULL,
    CONSTRAINT chk_maint_category CHECK (category IN ('ELECTRICAL', 'PLUMBING', 'HVAC', 'IT_NETWORK', 'GENERAL')),
    CONSTRAINT chk_maint_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_maint_status   CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED'))
);

-- -----------------------------------------------------------------------------
-- 3.7. Индексы
-- -----------------------------------------------------------------------------
CREATE INDEX idx_premises_type_status      ON premises (premises_type, status);
CREATE INDEX idx_contracts_premises_dates  ON lease_contracts (premises_id, start_date, end_date);
CREATE INDEX idx_contracts_status          ON lease_contracts (status);
CREATE INDEX idx_payments_contract_status  ON payments (contract_id, status, due_date);
CREATE INDEX idx_maintenance_status_priority ON maintenance_requests (status, priority, created_at);
