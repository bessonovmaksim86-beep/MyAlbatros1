-- ============================================================
-- Примечание для операций
-- ============================================================

ALTER TABLE operations
    ADD COLUMN note VARCHAR(2000) NULL;


-- ============================================================
-- Типы рабочих мест
-- ============================================================

CREATE TABLE work_place_types
(
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    code   VARCHAR(40)  NOT NULL,
    name   VARCHAR(255) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_work_place_types
        PRIMARY KEY (id),

    CONSTRAINT uk_work_place_type_code
        UNIQUE (code),

    CONSTRAINT uk_work_place_type_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


INSERT INTO work_place_types (code, name, active)
VALUES ('OSN', 'Основное', TRUE),
       ('SBR', 'Сборочное', TRUE),
       ('NST', 'Настроечное', TRUE),
       ('KON', 'Контрольное', TRUE),
       ('ISP', 'Испытательное', TRUE),
       ('SKL', 'Складское', TRUE);


-- ============================================================
-- Рабочие места
-- Состав параметров повторяет справочник операций,
-- код формируется автоматически в виде РМ 00.01
-- ============================================================

CREATE TABLE work_places
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(50)   NOT NULL,
    name                 VARCHAR(255)  NOT NULL,
    work_place_type_id   BIGINT        NOT NULL,
    executor_role_id     BIGINT        NOT NULL,
    service_id           BIGINT        NOT NULL,
    organization_unit_id BIGINT        NULL,
    note                 VARCHAR(2000) NULL,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)   NOT NULL,

    CONSTRAINT pk_work_places
        PRIMARY KEY (id),

    CONSTRAINT uk_work_place_code
        UNIQUE (code),

    CONSTRAINT fk_work_place_type
        FOREIGN KEY (work_place_type_id)
            REFERENCES work_place_types (id),

    CONSTRAINT fk_work_place_executor_role
        FOREIGN KEY (executor_role_id)
            REFERENCES user_roles (id),

    CONSTRAINT fk_work_place_service
        FOREIGN KEY (service_id)
            REFERENCES production_services (id),

    CONSTRAINT fk_work_place_organization_unit
        FOREIGN KEY (organization_unit_id)
            REFERENCES organization_units (id),

    INDEX idx_work_place_type
        (work_place_type_id),

    INDEX idx_work_place_service
        (service_id),

    INDEX idx_work_place_organization_unit
        (organization_unit_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Рабочие места, на которых выполняется операция техпроцесса
-- Для каждой операции маршрута обязательно хотя бы одно место
-- ============================================================

CREATE TABLE tech_process_operation_work_places
(
    tech_process_operation_id BIGINT NOT NULL,
    work_place_id             BIGINT NOT NULL,

    CONSTRAINT pk_tp_operation_work_place
        PRIMARY KEY (
            tech_process_operation_id,
            work_place_id
        ),

    CONSTRAINT fk_tp_operation_work_place_operation
        FOREIGN KEY (tech_process_operation_id)
            REFERENCES tech_process_operations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tp_operation_work_place_place
        FOREIGN KEY (work_place_id)
            REFERENCES work_places (id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
