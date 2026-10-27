-- ============================================================
-- V80 -- Расширение заявок на отбраковку
--
-- Добавление полей для цеховой отбраковки:
--   device_number   — номер изделия, на котором забракована ячейка
--   znp_number      — номер ЗНП, из которого забракована ячейка
--   controller_comment — комментарий контроллера при решении
--   defect_confirmed — подтверждён ли дефект (CONFIRMED/DENIED)
--   serial_id       — ссылка на ProductSerial для быстрых проверок
--
-- Все поля nullable: существующие заявки (V74) не затронуты.
-- ============================================================

ALTER TABLE pending_defects
    ADD COLUMN device_number VARCHAR(20) NULL
        AFTER decision,

    ADD COLUMN znp_number VARCHAR(100) NULL
        AFTER device_number,

    ADD COLUMN controller_comment VARCHAR(1000) NULL
        AFTER znp_number,

    ADD COLUMN defect_confirmed VARCHAR(20) NULL
        AFTER controller_comment,

    ADD COLUMN serial_id BIGINT NULL
        AFTER defect_confirmed;

-- Ссылка на серийный номер
ALTER TABLE pending_defects
    ADD CONSTRAINT fk_pending_defect_serial
        FOREIGN KEY (serial_id)
            REFERENCES product_serials (id);

-- Индекс для поиска заявок по ЗНП
ALTER TABLE pending_defects
    ADD INDEX idx_pending_defect_znp (znp_number);

-- Индекс для поиска заявок по серийному номеру
ALTER TABLE pending_defects
    ADD INDEX idx_pending_defect_serial (serial_id);

-- Проверка defect_confirmed
ALTER TABLE pending_defects
    ADD CONSTRAINT chk_pending_defect_confirmed
        CHECK (defect_confirmed IS NULL OR defect_confirmed IN ('CONFIRMED', 'DENIED'));
