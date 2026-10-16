-- ============================================================
-- V59: Контролируемые параметры операции берутся из справочника
-- ============================================================
-- V57 заводила для каждого вида параметра свою таблицу значений
-- (типы трубок, типы проволок) и колонку под каждую. Технолог
-- перевёл справочник параметров в раздел «Контролируемые
-- параметры» (V58): там параметр («Внесение типа трубки») и список
-- его вариантов («1 слойная 14мм», «42ХНТЮ», …).
--
-- Отдельные справочники при этом теряют смысл: их содержимое —
-- обычные строки-варианты, которые ведёт технолог тем же экраном.
-- Держать два источника для одного выбора нельзя, поэтому строка
-- операции начинает ссылаться на запись справочника:
--
--     control_param_id        — вид параметра,
--     control_param_value_id  — его значение,
--     mandatory               — чек-бокс «обязательное поле».
--
-- В таблице операций остаётся одна колонка значения вместо трёх,
-- а ограничение «одна строка — одно значение вида» наконец можно
-- выразить уникальным индексом: значение всегда заполнено, и NULL
-- больше не размывает уникальность (в V57 это мешало).
--
-- Миграция идемпотентна к состоянию базы: если V57 не успела
-- примениться (приложение с ней не запускали), старые таблицы
-- просто не удаляются, а создаётся итоговая.
-- ============================================================

DROP TABLE IF EXISTS tech_process_operation_control_params;

DROP TABLE IF EXISTS tube_types;

DROP TABLE IF EXISTS wire_types;

CREATE TABLE tech_process_operation_control_params
(
    id                        BIGINT  NOT NULL AUTO_INCREMENT,
    tech_process_operation_id BIGINT  NOT NULL,
    control_param_id          BIGINT  NOT NULL,
    control_param_value_id    BIGINT  NOT NULL,
    mandatory                 BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_tp_operation_control_params
        PRIMARY KEY (id),

    /*
     * Один вид параметра может быть указан в операции несколько раз
     * с разными значениями (например две трубки), но одно и то же
     * значение одного вида — только один раз.
     */
    CONSTRAINT uk_tp_operation_control_param
        UNIQUE (tech_process_operation_id, control_param_value_id),

    CONSTRAINT fk_tp_op_control_param_operation
        FOREIGN KEY (tech_process_operation_id)
            REFERENCES tech_process_operations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tp_op_control_param_param
        FOREIGN KEY (control_param_id)
            REFERENCES control_params (id),

    CONSTRAINT fk_tp_op_control_param_value
        FOREIGN KEY (control_param_value_id)
            REFERENCES control_param_values (id),

    INDEX idx_tp_op_control_param_operation
        (tech_process_operation_id),

    /*
     * Проверка при удалении варианта справочника: параметр,
     * зафиксированный в маршрутах, удалить нельзя.
     */
    INDEX idx_tp_op_control_param_value
        (control_param_value_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
