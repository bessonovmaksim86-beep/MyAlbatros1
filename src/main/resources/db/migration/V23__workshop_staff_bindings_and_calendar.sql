-- ============================================================
-- 1. Привязки сотрудников цеха к операциям и рабочим местам
--
-- Руководитель цеха закрепляет за сотрудником либо операцию
-- (из справочника операций, отнесённых к его цеху в разделе
-- «Исполнитель»), либо рабочее место того же цеха. Запись всегда
-- односторонняя: либо operation_id, либо work_place_id — это
-- закрыто проверкой chk_workshop_staff_binding_target.
--
-- Уникальность строки рассчитана по сгенерированным ключам:
-- в MySQL NULL не участвует в UNIQUE, поэтому «нет операции»
-- превращается в 0, и повторная привязка того же сотрудника
-- к той же операции отклоняется средствами схемы.
-- ============================================================

CREATE TABLE workshop_staff_bindings
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    employee_id     BIGINT      NOT NULL,
    binding_type    VARCHAR(20) NOT NULL,
    operation_id    BIGINT      NULL,
    work_place_id   BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL,

    operation_key   BIGINT
        GENERATED ALWAYS AS (IFNULL(operation_id, 0)) STORED,
    work_place_key  BIGINT
        GENERATED ALWAYS AS (IFNULL(work_place_id, 0)) STORED,

    CONSTRAINT pk_workshop_staff_bindings
        PRIMARY KEY (id),

    CONSTRAINT uk_workshop_staff_binding
        UNIQUE (
            employee_id,
            binding_type,
            operation_key,
            work_place_key
        ),

    CONSTRAINT chk_workshop_staff_binding_target
        CHECK (
            (
                binding_type = 'OPERATION'
                AND operation_id IS NOT NULL
                AND work_place_id IS NULL
            )
            OR
            (
                binding_type = 'WORK_PLACE'
                AND work_place_id IS NOT NULL
                AND operation_id IS NULL
            )
        ),

    CONSTRAINT fk_workshop_staff_binding_employee
        FOREIGN KEY (employee_id)
            REFERENCES app_users (id),

    CONSTRAINT fk_workshop_staff_binding_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_workshop_staff_binding_work_place
        FOREIGN KEY (work_place_id)
            REFERENCES work_places (id),

    INDEX idx_workshop_staff_binding_employee
        (employee_id, binding_type),

    INDEX idx_workshop_staff_binding_operation
        (operation_id),

    INDEX idx_workshop_staff_binding_work_place
        (work_place_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Производственный календарь сотрудника: отсутствия
--
-- Одна строка — один день одного сотрудника. Отметку ставят сразу
-- на несколько чисел, поэтому при сохранении создаётся по записи
-- на каждое выбранное число; UNIQUE (employee_id, absence_date)
-- не даёт пересечься двум видам отсутствия в один день.
-- ============================================================

CREATE TABLE employee_absences
(
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    employee_id  BIGINT        NOT NULL,
    absence_date DATE          NOT NULL,
    absence_type VARCHAR(20)   NOT NULL,
    note         VARCHAR(1000) NULL,
    created_at   DATETIME(6)   NOT NULL,

    CONSTRAINT pk_employee_absences
        PRIMARY KEY (id),

    CONSTRAINT uk_employee_absence_date
        UNIQUE (employee_id, absence_date),

    CONSTRAINT chk_employee_absence_type
        CHECK (
            absence_type IN ('VACATION', 'SICK', 'UNPAID')
        ),

    CONSTRAINT fk_employee_absence_employee
        FOREIGN KEY (employee_id)
            REFERENCES app_users (id),

    INDEX idx_employee_absence_date
        (absence_date, employee_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
