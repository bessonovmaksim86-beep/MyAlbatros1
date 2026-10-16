-- ============================================================
-- ЖУРНАЛ ПЕРЕМЕЩЕНИЙ: ПОЛНАЯ ИСТОРИЯ ЯЧЕЙКИ И ПОДСБОРКИ
--
-- V42 завёл журнал, но заполнялась в него лишь часть пути: склад
-- (приёмка, перемещение) и ремонтное комплектование писали строки, а
-- попадание в изолятор брака, возврат поставщику, создание и закрытие
-- ремонтного ЗНП — нет. По журналу нельзя было восстановить ни «сколько
-- раз ячейка падала в брак», ни «кто её забрал в ремонт».
--
-- Здесь закрываются две дыры сразу.
--
-- 1. СОБЫТИЯ. Добавлены начала и концы маршрута, без которых хронология
--    обрывалась: приход и выпуск (номер рождается), входной контроль
--    (первая отметка о самом изделии), возврат складом в изолятор
--    (перемещение V63 снималось совсем без следа) и отмена выпуска
--    (номер освобождался молча).
--
-- 2. РЕКВИЗИТЫ МОМЕНТА. Строка журнала обязана отвечать на вопрос
--    «какой ячейка была ТОГДА», а не «какая она сейчас». product_serials
--    перечёркивается следующим шагом, поэтому состояние на момент
--    события копируется в саму строку: статус реестра, разновидность
--    (подсборка/ячейка — граница плывёт при приёмке складом), изделие,
--    вид контроля. Иначе через месяц паспортом числилась бы текущая
--    стадия, а не та, на которой событие произошло.
--
-- Числа изоляций при этом обязан считать COUNT(QUARANTINED): одна карточка
-- входного контроля — одно попадание в изолятор, даже если номер
-- забракован по двум причинам сразу (V64). Поэтому событие пишут по
-- решению группы, а не по каждой её причине.
-- ============================================================


-- ============================================================
-- 1. НОВЫЕ ТИПЫ СОБЫТИЙ
--
-- MySQL не дополняет CHECK, а заменяет его, поэтому перечень
-- повторяют целиком — иначе ранее записанные события перестали бы
-- проходить проверку.
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
                'TRANSFERRED_TO_WAREHOUSE',
                'RETURNED_TO_QUARANTINE',
                'ACCEPTED',
                'IN_PRODUCTION',
                'RELEASE_CANCELLED'
            )
        );


-- ============================================================
-- 2. СОСТОЯНИЕ ЯЧЕЙКИ НА МОМЕНТ СОБЫТИЯ
--
-- Все колонки nullable: в журнал попадают и номера партий прихода,
-- ещё не внесённые в реестр, — для них статуса реестра не существует.
-- ============================================================

ALTER TABLE serial_movement_events
    ADD COLUMN serial_status VARCHAR(20) NULL AFTER event_type;

ALTER TABLE serial_movement_events
    ADD COLUMN cell_kind VARCHAR(20) NULL AFTER serial_status;

ALTER TABLE serial_movement_events
    ADD COLUMN product_classifier_id BIGINT NULL AFTER cell_kind;

ALTER TABLE serial_movement_events
    ADD COLUMN input_control_type VARCHAR(20) NULL
        AFTER product_classifier_id;


-- ============================================================
-- 3. ССЫЛКИ НА ДОКУМЕНТ-ИСТОЧНИК
--
-- Партия прихода и строка перемещения из изолятора — документы, по
-- которым событие произошло, но колонки для них в V42 не было: по
-- журналу видно, что ячейку передали складу, но не видно, куда именно
-- и по какому приходу.
-- ============================================================

ALTER TABLE serial_movement_events
    ADD COLUMN receiving_batch_id BIGINT NULL AFTER defect_group_id;

ALTER TABLE serial_movement_events
    ADD COLUMN quarantine_transfer_id BIGINT NULL
        AFTER receiving_batch_id;


-- ============================================================
-- 4. ГРУППА БРАКА БОЛЬШЕ НЕ УДАЛЯЕТСЯ ВМЕСТЕ С ПРОВОДКОЙ
--
-- fk_sme_defect_group (V42) объявлен без ON DELETE. Пока журнал на
-- группы не ссылался, это безразлично; с записью изоляции стало важно:
-- БВК перезаписывает группы брака при каждом сохранении карточки
-- (InputControlService#rewriteDefectGroups), и предписанный ключ
-- блокировал бы это удаление, а вместе с ним и весь входной контроль.
--
-- Ссылку снимают, строку журнала сохраняют: причина уже скопирована в
-- note, а сам факт изоляции обязан пережить переписывание карточки.
-- ============================================================

ALTER TABLE serial_movement_events
    DROP FOREIGN KEY fk_sme_defect_group;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT fk_sme_defect_group
        FOREIGN KEY (defect_group_id)
            REFERENCES input_control_defect_groups (id)
            ON DELETE SET NULL;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT fk_sme_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id)
            ON DELETE SET NULL;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT fk_sme_receiving_batch
        FOREIGN KEY (receiving_batch_id)
            REFERENCES cell_receiving_batches (id)
            ON DELETE SET NULL;

/*
 * Перемещение из изолятора склад снимает (возврат в изолятор), и
 * предписанный ключ обязан это пережить — по той же причине, что и
 * ссылка на группу брака.
 */
ALTER TABLE serial_movement_events
    ADD CONSTRAINT fk_sme_quarantine_transfer
        FOREIGN KEY (quarantine_transfer_id)
            REFERENCES quarantine_transfers (id)
            ON DELETE SET NULL;

/* Паспорт номера читают хронологией; индекс V42 покрывает и новые строки. */
CREATE INDEX idx_sme_classifier
    ON serial_movement_events (product_classifier_id, occurred_at);
