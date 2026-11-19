-- ============================================================
-- V89 — ЗАЯВКА НА ОТБРАКОВКУ — СОБЫТИЕ ПУТИ НОМЕРА
--
-- Заявку на отбраковку цех оформляет в разделе «Забраковать», а
-- перемещение в изолятор бюро проводит своим решением. До этого типа
-- журнал показывал только второй шаг: в хронологии ячейки между
-- «Принята подразделением» и «Перемещена в изолятор брака» зиял
-- провал, и по паспорту нельзя было узнать, кто именно инициировал
-- брак, когда и по какой причине.
--
-- Следствие видно на разборе брака: мастер утверждает, что ячейку
-- забраковал цех, а паспорт отвечает лишь строкой контролёра БВК —
-- заявка цеха в нём не читается вовсе. Отдельное событие нужно и
-- потому, что заявка живёт дольше своего решения: пока бюро молчит,
-- номер обязан нести отметку «забракован цехом, ждёт решения», а не
-- выглядеть изделием, которое просто никто не проводит.
--
-- Добавлен тип SCRAP_REQUESTED. Его пишут
-- SerialMovementJournalService#recordScrapRequested из
-- WorkshopScrapService#createScrapRequest (заявка цеха) и из
-- PendingDefectService#create (заявка бюро входного контроля).
--
-- Решение бюро отдельного типа не получает: оно уже пишет QUARANTINED
-- через PendingDefectService#moveToQuarantine, и вторая строка того же
-- шага удвоила бы хронологию (см. V88 о возврате с контроля).
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
