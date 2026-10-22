-- ============================================================
-- V75 -- Признак «готовая ячейка»
--
-- Разделяем «лежит на складе» (accepted_at) и «готовая ячейка»
-- (cell_ready_at). До V75 принятая после БВК позиция мгновенно
-- становилась «ячейкой» во всех отборах: граница CELL/SUBASSEMBLY
-- выводилась из accepted_at (см. QuarantineCellKind.ofAccepted).
--
-- accepted_at — момент, когда кладовщик принял партию после БВК
-- (или внёс подсборку в ЗНП режима V72): позиция лежит на складе,
-- но остаётся подсборкой.
--
-- cell_ready_at — момент, когда кладовщик отметил позицию готовой
-- ячейкой: галочка «отметить как готовые ячейки» в «Приёмке с БВК».
-- Только готовые ячейки выходят в комплектование и числятся ячейками
-- в изоляторе брака, реестре наличия и заявках на отбраковку.
--
-- Обратная совместимость: все позиции, принятые складом до V75
-- (accepted_at уже стоит), получают cell_ready_at = accepted_at —
-- поведение существующих записей не меняется.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN cell_ready_at DATETIME(6) NULL,
    ADD COLUMN cell_ready_by BIGINT NULL,
    ADD CONSTRAINT fk_product_serial_cell_ready_by
        FOREIGN KEY (cell_ready_by) REFERENCES app_users(id);

-- Принятые после БВК до V75 — уже ячейки: отметка переносится 1:1.
UPDATE product_serials
SET cell_ready_at = accepted_at,
    cell_ready_by = accepted_by
WHERE accepted_at IS NOT NULL;

/*
 * Отметка готовности на партии — по тому же мотиву, что и приёмка
 * партии (accepted_at на cell_receiving_batches): перечни изолятора
 * и брака фильтруют партии, а не позиции реестра, и разновидность
 * строки выводят из отметки партии (InputControlService.DefectRecordView,
 * findQuarantineGroupsFiltered).
 */
ALTER TABLE cell_receiving_batches
    ADD COLUMN cell_ready_at DATETIME(6) NULL,
    ADD COLUMN cell_ready_by BIGINT NULL,
    ADD CONSTRAINT fk_cell_receiving_cell_ready_by
        FOREIGN KEY (cell_ready_by) REFERENCES app_users(id);

UPDATE cell_receiving_batches
SET cell_ready_at = accepted_at,
    cell_ready_by = accepted_by
WHERE accepted_at IS NOT NULL;

/*
 * Новое событие журнала: «Отмечена готовой ячейкой». Отметку ставят
 * пакетом вместе с приёмкой, но в паспорте это отдельный факт —
 * между «Принята на склад» (лежит как подсборка) и «Оформлена в заказ»
 * позиция обязана показывать, когда именно стала ячейкой.
 */
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
                'RELEASE_CANCELLED'
            )
        );
