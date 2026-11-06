-- ============================================================
-- V81 -- Подтверждаемый переход позиции со склада в подразделение
--
-- Когда склад вносит ячейку (или подсборку) в заказ другого
-- подразделения, позиция не уходит со склада мгновенно: она
-- переходит в статус «Передана, ждёт подтверждения». Получатель
-- открывает реестр «Приёмка от склада», сверяет номера и
-- подтверждает получение — после этого позиция снимается со
-- склада и появляется в распоряжении цеха.
--
-- Если получатель отклоняет позицию, она возвращается на склад
-- и остаётся в доступном остатке.
--
-- Состав проводится в отдельной таблице, а не отметкой на ячейке:
-- так же, как quarantine_transfers (V63) и repair_kitting_items (V52),
-- — чтобы реестр склада мог видеть незакрытые передачи и исключать
-- их из доступного остатка.
-- ============================================================

CREATE TABLE unit_handovers
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    /* Полный номер ячейки/подсборки: 4 цифры кода + 5 цифр серии. */
    full_number      VARCHAR(20)   NOT NULL,

    /* Ссылка на серийный номер — для быстрых проверок и журналов. */
    serial_id        BIGINT        NULL,

    /*
     * Классификатор — наименование для перечней.
     * NULL допустим: номера могут попадать сюда из партий прихода,
     * ещё не внесённых в реестр.
     */
    product_classifier_id BIGINT  NULL,

    /* SUBASSEMBLY / CELL — разновидность на момент передачи. */
    cell_kind        VARCHAR(20)   NOT NULL,

    /* Подразделение-отправитель (склад полуфабрикатов). */
    from_unit_id     BIGINT        NOT NULL,

    /* Подразделение-получатель (цех сборки / настройки и т.п.). */
    to_unit_id       BIGINT        NOT NULL,

    /* Заказ-комплектующий, по которому передана позиция. */
    to_order_id      BIGINT        NULL,

    /* Номер заказа — чтобы не грузить связь при каждом чтении. */
    to_order_number  VARCHAR(50)   NULL,

    /* Операция, по которой передана позиция. */
    to_operation_id  BIGINT        NULL,

    /* Кто и когда передал позицию. */
    transferred_by   BIGINT        NOT NULL,
    transferred_at   DATETIME(6)   NOT NULL,

    /* Принято ли получателем. */
    accepted         BOOLEAN       NOT NULL DEFAULT FALSE,
    accepted_by      BIGINT        NULL,
    accepted_at      DATETIME(6)   NULL,

    /* Отклонено ли получателем и причина. */
    rejected         BOOLEAN       NOT NULL DEFAULT FALSE,
    rejection_reason VARCHAR(1000) NULL,
    rejected_by      BIGINT        NULL,
    rejected_at      DATETIME(6)   NULL,

    CONSTRAINT pk_unit_handovers
        PRIMARY KEY (id),

    CONSTRAINT fk_unit_handover_serial
        FOREIGN KEY (serial_id)
            REFERENCES product_serials (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_unit_handover_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_unit_handover_from_unit
        FOREIGN KEY (from_unit_id)
            REFERENCES organization_units (id),

    CONSTRAINT fk_unit_handover_to_unit
        FOREIGN KEY (to_unit_id)
            REFERENCES organization_units (id),

    CONSTRAINT fk_unit_handover_to_order
        FOREIGN KEY (to_order_id)
            REFERENCES production_orders (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_unit_handover_to_operation
        FOREIGN KEY (to_operation_id)
            REFERENCES operations (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_unit_handover_transferred_by
        FOREIGN KEY (transferred_by)
            REFERENCES app_users (id),

    CONSTRAINT fk_unit_handover_accepted_by
        FOREIGN KEY (accepted_by)
            REFERENCES app_users (id),

    CONSTRAINT fk_unit_handover_rejected_by
        FOREIGN KEY (rejected_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_unit_handover_kind
        CHECK (cell_kind IN ('SUBASSEMBLY', 'CELL'))
);

/* Незакрытые передачи — основной путь чтения перечня приёмки. */
CREATE INDEX idx_unit_handover_pending
    ON unit_handovers (accepted, rejected, transferred_at);

/* Поиск «передавалась ли позиция» по номеру. */
CREATE INDEX idx_unit_handover_number
    ON unit_handovers (full_number);

/* Передачи в конкретное подразделение. */
CREATE INDEX idx_unit_handover_to_unit
    ON unit_handovers (to_unit_id, accepted, rejected);

/* Передачи по заказу. */
CREATE INDEX idx_unit_handover_order
    ON unit_handovers (to_order_id);


-- ============================================================
-- НОВЫЕ ТИПЫ СОБЫТИЙ ЖУРНАЛА: ПЕРЕДАЧА И ПРИЁМКА ПОЗИЦИИ
--
-- Событие UNIT_TRANSFERRED — факт передачи со склада в подразделение.
-- Событие UNIT_ACCEPTED — факт подтверждения получения.
-- Событие UNIT_REJECTED — факт отклонения позиции получателем.
--
-- MySQL не дополняет CHECK, а заменяет его, поэтому перечень
-- повторяется целиком — иначе ранее записанные события перестали
-- бы проходить проверку.
-- ============================================================

ALTER TABLE serial_movement_events
    DROP CHECK chk_sme_event_type;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT chk_sme_event_type
        CHECK (
            event_type IN (
                'RECEIVED',
                'ISSUED',
                'INPUT_CONTROL_PASSED',
                'QUARANTINED',
                'RETURNED_TO_SUPPLIER',
                'REPAIR_ORDERED',
                'REPAIR_DONE',
                'REPAIR_CANCELLED',
                'KITTED_TO_REPAIR_ZNP',
                'IN_PRODUCTION',
                'ACCEPTED',
                'CELL_READY',
                'TRANSFERRED_TO_WAREHOUSE',
                'RETURNED_TO_QUARANTINE',
                'RELEASE_CANCELLED',
                'UNIT_TRANSFERRED',
                'UNIT_ACCEPTED',
                'UNIT_REJECTED'
            )
        );

/*
 * Ссылка на строку передачи — по тому же мотиву, что и ссылка на
 * перемещение из изолятора (V65): по журналу видно, куда именно
 * ушла позиция, а не только то, что она ушла.
 */
ALTER TABLE serial_movement_events
    ADD COLUMN unit_handover_id BIGINT NULL AFTER quarantine_transfer_id;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT fk_sme_unit_handover
        FOREIGN KEY (unit_handover_id)
            REFERENCES unit_handovers (id)
            ON DELETE SET NULL;
