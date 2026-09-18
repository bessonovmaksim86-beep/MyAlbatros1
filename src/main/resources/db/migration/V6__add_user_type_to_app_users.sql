-- ============================================================
-- User role inside a service
-- (SERVICE_HEAD / HEAD_OF_WORKSHOP_OR_DEPARTMENT / EXECUTOR).
--
-- AppUser.roleUser (@Enumerated STRING, length 40) maps to this
-- column, but it was dropped from app_users while V1 was rewritten,
-- so Hibernate schema-validation failed with:
--   Schema-validation: missing column [user_type] in table [app_users]
--
-- V1 is intentionally NOT edited: changing an already applied
-- migration breaks Flyway checksum validation on existing databases.
-- Adding the column here also works for clean installs
-- (V1 creates the table, V6 extends it).
-- ============================================================

ALTER TABLE app_users
    ADD COLUMN user_type VARCHAR(40) NULL AFTER organization_unit_id;
