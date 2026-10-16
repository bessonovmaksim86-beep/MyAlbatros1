-- ============================================================
-- V57: Контролируемые параметры операций техпроцесса
-- ============================================================
-- В каждую операцию маршрута добавляется блок «Контролируемые
-- параметры» (для датчиков). Заполнение — по тому же принципу,
-- что и рабочие места: как только строка заполнена, появляется
-- следующая пустая.
--
-- Столбцы строки параметра:
--   1. Вид параметра — выпадающий список:
--        CELL — внесение номера ячейки,
--        TUBE — внесение типа трубки (для датчиков),
--        WIRE — внесение типа проволоки (для датчиков);
--   2. Значение — выпадающий список, состав зависит от вида:
--        для CELL это ячейки, входящие в изделие-датчик
--        (берутся из классификатора),
--        для TUBE и WIRE — отдельные справочники ниже
--        (пока по одной записи: 42ХНТЮ для проволоки,
--         1 слойная 14мм для трубки);
--   3. Чек-бокс «обязательное поле».
--
-- Значение хранится в своей колонке, а не в общей: ячейка — это
-- изделие классификатора, трубка и проволока — записи своих
-- справочников, приведение их к одному «универсальному» значению
-- лишило бы внешние ключи силы.
--
-- Ограничение «один вид — одно значение» и заполненность значения
-- проверяет сервис: уникальный индекс здесь не работает, так как
-- у строки заполнена ровно одна из трёх колонок значения, а MySQL
-- не считает NULL-ы повторяющимися.
-- ============================================================

-- Справочник типов трубок
CREATE TABLE tube_types
(
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    name  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_tube_types
        PRIMARY KEY (id),

    CONSTRAINT uk_tube_types_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Справочник типов проволок
CREATE TABLE wire_types
(
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    name  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_wire_types
        PRIMARY KEY (id),

    CONSTRAINT uk_wire_types_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Начальные значения справочников: по одной записи, как указал
-- технолог. Дальше справочники наполняют вручную.
INSERT INTO wire_types (id, name)
VALUES (1, '42ХНТЮ');

INSERT INTO tube_types (id, name)
VALUES (1, '1 слойная 14мм');

-- Контролируемые параметры операции маршрута
CREATE TABLE tech_process_operation_control_params
(
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    tech_process_operation_id BIGINT      NOT NULL,
    param_type                VARCHAR(10) NOT NULL,
    cell_classifier_id        BIGINT      NULL,
    tube_type_id              BIGINT      NULL,
    wire_type_id              BIGINT      NULL,
    mandatory                 BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_tp_operation_control_params
        PRIMARY KEY (id),

    CONSTRAINT fk_tp_op_control_param_operation
        FOREIGN KEY (tech_process_operation_id)
            REFERENCES tech_process_operations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tp_op_control_param_cell
        FOREIGN KEY (cell_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_tp_op_control_param_tube
        FOREIGN KEY (tube_type_id)
            REFERENCES tube_types (id),

    CONSTRAINT fk_tp_op_control_param_wire
        FOREIGN KEY (wire_type_id)
            REFERENCES wire_types (id),

    CHECK (param_type IN ('CELL', 'TUBE', 'WIRE')),

    INDEX idx_tp_op_control_param_operation
        (tech_process_operation_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
