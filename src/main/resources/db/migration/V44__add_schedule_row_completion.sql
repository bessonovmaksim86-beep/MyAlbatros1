-- ============================================================
-- Проведение строки графика: факт выполнения экземпляра
--
-- Строка order_schedule_rows — это одна операция одного экземпляра
-- одной позиции (V43). Назначение на исполнителя (executor_id, V43)
-- отвечает на вопрос «кто делает», но не фиксирует, что работа
-- сделана. Проведение — отдельный факт: дата и автор отметки, по
-- аналогии с product_serial_operations (V19: actual_date, performed_by).
--
-- Проводит исполнитель, на которого строка закреплена; руководитель
-- работу назначает, но не проводит. Поэтому поля разведены:
-- executor_id — назначение, completed_by — кто отметил факт.
--
-- Проведение поштучное: позиция на десять изделий даёт десять
-- строк, и закрываться они должны каждая своим фактом — иначе
-- «сделано 3 из 10» было бы не выразить одним статусом группы.
-- ============================================================

ALTER TABLE order_schedule_rows
    ADD COLUMN completed_at DATETIME(6) NULL
        AFTER assigned_at;

ALTER TABLE order_schedule_rows
    ADD COLUMN completed_by BIGINT NULL
        AFTER completed_at;

ALTER TABLE order_schedule_rows
    ADD CONSTRAINT fk_osr_completed_by
        FOREIGN KEY (completed_by)
        REFERENCES app_users (id);

/*
 * Список «моих незакрытых работ» — главный запрос проведения:
 * строки исполнителя, ещё не отмеченные фактом, по дате плана.
 */
ALTER TABLE order_schedule_rows
    ADD INDEX idx_osr_executor_completion
        (executor_id, completed_at, planned_date);
