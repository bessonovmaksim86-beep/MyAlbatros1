-- ============================================================
-- V94 — ПРИВЯЗКА ЯЧЕЙКИ К ПРИБОРУ — СОБЫТИЕ ПУТИ НОМЕРА
--
-- Исполнитель вкладывает ячейку в конкретное изделие и фиксирует это
-- в таблице cell_instrument_bindings (V91). До этого типа журнал
-- показывал только «Вошла в выпуск производства»: в хронологии ячейки
-- между выпуском и операцией монтажа не было строки, и по паспорту
-- нельзя было узнать, в какой именно прибор ячейка вошла и когда.
--
-- Следствие видно на разборе изделия: мастер утверждает, что трубку
-- вложили в первый прибор, а паспорт отвечает лишь строкой выпуска —
-- привязка в нём не читается вовсе. Отдельное событие нужно и потому,
-- что привязку правят: прежнюю запись снимают, создают новую, и по
-- хронологии обязано быть видно, когда номер попадал в изделие и
-- когда его отвязывали.
--
-- Добавлен тип ENTERED_INSTRUMENT. Его пишет
-- SerialMovementJournalService#recordEnteredInstrument из
-- DeviceCellBindingService#bind (привязка номера к прибору).
--
-- Снятие привязки отдельного типа не получает: правка номера — тот же
-- шаг с новой записью (см. DeviceCellBindingService#unlinkPrevious),
-- и вторая строка того же шага удвоила бы хронологию (см. V88 о
-- возврате с контроля).
-- ============================================================


-- ============================================================
-- НОВЫЙ ТИП СОБЫТИЯ
--
-- MySQL не дополняет CHECK, а заменяет его, поэтому перечень
-- повторяется целиком — иначе ранее записанные события перестали бы
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
                'SCRAP_REQUESTED',
                'QUARANTINED',
                'RETURNED_TO_SUPPLIER',
                'REPAIR_ORDERED',
                'REPAIR_DONE',
                'REPAIR_CANCELLED',
                'KITTED_TO_REPAIR_ZNP',
                'IN_PRODUCTION',
                'ENTERED_INSTRUMENT',
                'ACCEPTED',
                'CELL_READY',
                'TRANSFERRED_TO_WAREHOUSE',
                'RETURNED_TO_QUARANTINE',
                'RELEASE_CANCELLED',
                'UNIT_TRANSFERRED',
                'UNIT_ACCEPTED',
                'UNIT_REJECTED',
                'OPERATION_COMPLETED',
                'RETURNED_TO_REWORK'
            )
        );
