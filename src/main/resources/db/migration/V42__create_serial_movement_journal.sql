-- ============================================================
-- ЖУРНАЛ ПЕРЕМЕЩЕНИЙ ЯЧЕЙКИ ВОКРУГ НЕИЗМЕННОГО НОМЕРА
--
-- Серийный номер ходит по кругу: изолятор брака → ремонтный ЗНП →
-- производство → снова изолятор. product_serials хранит только текущее
-- состояние, product_serial_operations — план/факт маршрута выпуска и
-- для ремонта не годится (у ремонта нет ни операции, ни заказа
-- производства). История попаданий живёт отдельным журналом: строка =
-- событие с датой, исполнителем, заказом и причиной.
--
-- Номер пишут строкой full_number (как RepairOrderItem, V34): в
-- изолятор попадают и номера «всей партией», ещё не внесённые в
-- product_serials, поэтому ссылка на реестр необязательна, а CHECK
-- требует хотя бы один из двух идентификаторов.
--
-- Цикл брака = число QUARANTINED по номеру; простой на ремонте = пара
-- REPAIR_ORDERED → REPAIR_DONE; трудоёмкость = разбивка по actor_id.
-- ============================================================

CREATE TABLE serial_movement_events
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    /* Строка реестра, если номер в него уже внесён. */
    serial_id           BIGINT        NULL,

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    full_number         VARCHAR(20)   NULL,

    QUARANTINED_MARK    VARCHAR(1)    NULL,

    /* QUARANTINED, RETURNED_TO_SUPPLIER, REPAIR_ORDERED, REPAIR_DONE,
       REPAIR_CANCELLED, IN_PRODUCTION, ACCEPTED. */
    event_type          VARCHAR(30)   NOT NULL,

    /* Момент события — по нему строят хронологию и считают простои. */
    occurred_at         DATETIME(6)   NOT NULL,

    /* Исполнитель отметки: контролёр, диспетчер, кладовщик. */
    actor_id            BIGINT        NULL,

    /* Заказ на ремонт, если событие относится к ЗНП. */
    repair_order_id     BIGINT        NULL,

    /* Заказ производства, если номер вошёл в выпуск. */
    production_order_id BIGINT        NULL,

    /* Группа брака-источник: по ней восстановлены причина и партия. */
    defect_group_id     BIGINT        NULL,

    note                VARCHAR(500)  NULL,

    CONSTRAINT pk_serial_movement_events
        PRIMARY KEY (id),

    /* Номер обязан читаться хотя бы одним из двух идентификаторов. */
    CONSTRAINT chk_sme_has_identifier
        CHECK (serial_id IS NOT NULL OR full_number IS NOT NULL),

    CONSTRAINT chk_sme_event_type
        CHECK (
            event_type IN (
                'QUARANTINED',
                'RETURNED_TO_SUPPLIER',
                'REPAIR_ORDERED',
                'REPAIR_DONE',
                'REPAIR_CANCELLED',
                'IN_PRODUCTION',
                'ACCEPTED'
            )
        ),

    CONSTRAINT fk_sme_serial
        FOREIGN KEY (serial_id)
            REFERENCES product_serials (id),

    CONSTRAINT fk_sme_actor
        FOREIGN KEY (actor_id)
            REFERENCES app_users (id),

    CONSTRAINT fk_sme_repair_order
        FOREIGN KEY (repair_order_id)
            REFERENCES repair_orders (id),

    CONSTRAINT fk_sme_production_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id),

    CONSTRAINT fk_sme_defect_group
        FOREIGN KEY (defect_group_id)
            REFERENCES input_control_defect_groups (id),

    /* Хронология номера — главный запрос журнала. */
    INDEX idx_sme_full_number (full_number, occurred_at),
    INDEX idx_sme_serial (serial_id, occurred_at),
    INDEX idx_sme_repair_order (repair_order_id),
    INDEX idx_sme_type (event_type, occurred_at)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;