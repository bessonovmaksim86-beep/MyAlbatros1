-- V71: сохранение оригинальной даты плана при переносе непроведённых операций.
-- Добавляем колонку, заполняем текущими датами, делаем NOT NULL.

ALTER TABLE order_schedule_rows
    ADD COLUMN original_planned_date DATE NULL;

UPDATE order_schedule_rows
SET original_planned_date = planned_date;

ALTER TABLE order_schedule_rows
    MODIFY COLUMN original_planned_date DATE NOT NULL;

-- Индекс для поиска по оригинальной дате (перенос, аналитика)
CREATE INDEX idx_osr_original_planned_date ON order_schedule_rows(original_planned_date);
