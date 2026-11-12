-- ============================================================
-- V88 — ВОЗВРАТ С КОНТРОЛЯ БТК НА ДОРАБОТКУ — СОБЫТИЕ ПУТИ НОМЕРА
--
-- Возврат с контроля живёт в таблице control_returns (V82): она держит
-- сигнал цеху и причину, но читается только подсветкой в «Мои
-- операции». Паспорт изделия собирается по журналу перемещений, и
-- до этого типа возврат в нём не появлялся вовсе.
--
-- Следствие видно на повторной переделке: изделие вернули с контроля,
-- цех переделал, БТК проверил снова и вернул ещё раз — а в хронологии
-- номера обе переделки выглядят одним провалом проведения. Без
-- отдельного события нельзя сказать, сколько раз работу возвращали и
-- с какими причинами, — именно это и различает первую переделку от
-- повторной.
--
-- Добавлен тип RETURNED_TO_REWORK. Его пишет
-- SerialMovementJournalService#recordReturnedToRework из
-- ControlReturnService#sendToRework — по каждой затронутой строке
-- плана, как и все прочие события журнала.
--
-- Действие «в изолятор брака» отдельного типа не требует: оно ведёт
-- заявку отбраковки, а заявка уже пишет QUARANTINED через
-- PendingDefectService#moveToQuarantine — вторая строка того же шага
-- удвоила бы хронологию.
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
                'UNIT_REJECTED',
                'OPERATION_COMPLETED',
                'RETURNED_TO_REWORK'
            )
        );
