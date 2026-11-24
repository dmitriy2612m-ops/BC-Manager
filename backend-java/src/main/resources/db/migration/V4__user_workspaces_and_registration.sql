-- =============================================================================
-- BC-Manager — персональные рабочие пространства пользователей (Flyway V4)
-- Регистрация новых пользователей с изолированным пустым аккаунтом.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN personal_workspace BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE premises
    ADD COLUMN owner_id BIGINT NULL REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE tenants
    ADD COLUMN owner_id BIGINT NULL REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE lease_contracts
    ADD COLUMN owner_id BIGINT NULL REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE maintenance_requests
    ADD COLUMN owner_id BIGINT NULL REFERENCES users(id) ON DELETE CASCADE;

-- Уникальность кодов помещений, ИНН арендаторов и номеров договоров
-- ограничивается рамками рабочего пространства (общее демо: owner_id IS NULL,
-- персональные аккаунты: owner_id IS NOT NULL).
ALTER TABLE premises DROP CONSTRAINT IF EXISTS premises_code_key;
CREATE UNIQUE INDEX uq_premises_code_shared ON premises (code) WHERE owner_id IS NULL;
CREATE UNIQUE INDEX uq_premises_code_owner  ON premises (owner_id, code) WHERE owner_id IS NOT NULL;

ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_inn_key;
CREATE UNIQUE INDEX uq_tenants_inn_shared ON tenants (inn) WHERE owner_id IS NULL;
CREATE UNIQUE INDEX uq_tenants_inn_owner  ON tenants (owner_id, inn) WHERE owner_id IS NOT NULL;

ALTER TABLE lease_contracts DROP CONSTRAINT IF EXISTS lease_contracts_contract_number_key;
CREATE UNIQUE INDEX uq_contracts_number_shared ON lease_contracts (contract_number) WHERE owner_id IS NULL;
CREATE UNIQUE INDEX uq_contracts_number_owner  ON lease_contracts (owner_id, contract_number) WHERE owner_id IS NOT NULL;

CREATE INDEX idx_premises_owner    ON premises (owner_id);
CREATE INDEX idx_tenants_owner     ON tenants (owner_id);
CREATE INDEX idx_contracts_owner   ON lease_contracts (owner_id);
CREATE INDEX idx_maintenance_owner ON maintenance_requests (owner_id);
