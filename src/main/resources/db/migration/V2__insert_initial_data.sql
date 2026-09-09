-- ============================================================
-- Производственные службы
-- ============================================================

INSERT INTO production_services
    (code, name, active)
VALUES
    ('СДП', 'Служба директора по производству', TRUE),
    ('СК',  'Служба качества', TRUE),
    ('СКД', 'Служба коммерческого директора', TRUE),
    ('СГМ', 'Служба главного метролога', TRUE);


-- ============================================================
-- Организационные подразделения
-- ============================================================
--
-- Значения WORKSHOP и DEPARTMENT должны точно совпадать
-- с константами OrganizationUnitType.
-- ============================================================

INSERT INTO organization_units
    (code, name, type, service_id, active)
VALUES
    (
        'WORKSHOP_ASSEMBLY',
        'Сборочный цех',
        'WORKSHOP',
        (
            SELECT id
            FROM production_services
            WHERE code = 'СДП'
        ),
        TRUE
    ),
    (
        'WORKSHOP_TEST',
        'Цех испытаний',
        'WORKSHOP',
        (
            SELECT id
            FROM production_services
            WHERE code = 'СДП'
        ),
        TRUE
    ),
    (
        'QUALITY',
        'Отдел технического контроля',
        'DEPARTMENT',
        (
            SELECT id
            FROM production_services
            WHERE code = 'СК'
        ),
        TRUE
    ),
    (
        'TECHNOLOGY',
        'Технологический отдел',
        'DEPARTMENT',
        (
            SELECT id
            FROM production_services
            WHERE code = 'СДП'
        ),
        TRUE
    );