-- ============================================================
-- Роли пользователей
-- ============================================================

INSERT INTO user_roles
    (code, display_name)
VALUES
    ('SERVICE_HEAD', 'Руководитель службы'),
    (
        'HEAD_OF_WORKSHOP_OR_DEPARTMENT',
        'Начальник цеха/отдела'
    ),
    ('EXECUTOR', 'Исполнитель');


-- ============================================================
-- Производственные службы
-- ============================================================

INSERT INTO production_services
    (code, name, active)
VALUES
    (
        'SDP',
        'Служба директора по производству',
        TRUE
    ),
    (
        'SQ',
        'Служба качества',
        TRUE
    ),
    (
        'SCD',
        'Служба коммерческого директора',
        TRUE
    ),
    (
        'SGM',
        'Служба главного метролога',
        TRUE
    )
    ,
        (
            'SGD',
            'Служба генерального директора',
            TRUE
        );


-- ============================================================
-- Организационные подразделения
-- ============================================================

INSERT INTO organization_units
    (code, name, service_id, active)
VALUES
    (
        'WORKSHOP_ASSEMBLY',
        'Цех сборки',
        (
            SELECT id
            FROM production_services
            WHERE code = 'SDP'
        ),
        TRUE
    ),
     (
            'WORKSHOP_PRODUCTION_PREPARATION',
            'Цех подготовки производства',
            (
                SELECT id
                FROM production_services
                WHERE code = 'SDP'
            ),
            TRUE
        ),
         (
                    'WORKSHOP_SETUP',
                    'Цех настройки',
                    (
                        SELECT id
                        FROM production_services
                        WHERE code = 'SDP'
                    ),
                    TRUE
                ),
    (
        'WORKSHOP_TEST',
        'Цех испытаний',
        (
            SELECT id
            FROM production_services
            WHERE code = 'SDP'
        ),
        TRUE
    ),
    (
            'ASSEMBLY_WAREHOUSE',
            'Склад комплектации',
            (
                SELECT id
                FROM production_services
                WHERE code = 'SGD'
            ),
            TRUE
        ),
        (
                    'SEMI_FINISHED_PRODUCT_WAREHOUSE',
                    'Склад полуфабрикатов',
                    (
                        SELECT id
                        FROM production_services
                        WHERE code = 'SGD'
                    ),
                    TRUE
                ),

    (
        'TECHNICAL_CONTROL_DEPARTMENT',
        'Отдел технического контроля',
        (
            SELECT id
            FROM production_services
            WHERE code = 'SQ'
        ),
        TRUE
    ),
    (
        'TECHNOLOGY',
        'Технологическое бюро',
        (
            SELECT id
            FROM production_services
            WHERE code = 'SDP'
        ),
        TRUE
    ),
         (
             'METROLOGY_DEPARTMENT',
             'Метрологический отдел',
             (
                 SELECT id
                 FROM production_services
                 WHERE code = 'SGM'
             ),
             TRUE
         )
         ,
                  (
                      'FINISHED_PRODUCT_WAREHOUS',
                      'Склад готовой продукции',
                      (
                          SELECT id
                          FROM production_services
                          WHERE code = 'SCD'
                      ),
                      TRUE
                  );

-- ============================================================
-- Типы операций
-- ============================================================

INSERT INTO operation_types
    (code, name, active)
VALUES
    (
        'PRODUCTION',
        'Производственная',
        TRUE
    ),
    (
        'TRANSFER',
        'Передаточная',
        TRUE
    ),
    (
        'RETURN',
        'Возвратная',
        TRUE
    ),
    (
        'WORK_DISTRIBUTION',
        'Распределение работ',
        TRUE
    ),
    (
        'KIT_RECEIPT',
        'Получение комплектации',
        TRUE
    );
