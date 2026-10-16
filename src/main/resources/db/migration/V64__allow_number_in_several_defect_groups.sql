-- ============================================================
-- ОДИН НОМЕР — НЕСКОЛЬКО ГРУПП БРАКА
--
-- Ячейку бракуют по нескольким основаниям сразу: «дефект покрытия» и
-- «коррозия» на одном номере — не ошибка контролёра, а два разных
-- дефекта одной ячейки. Прежний ключ uk_input_control_cell запрещал
-- такой состав на уровне строки, и вторая группа с тем же номером
-- отклонялась ещё до записи.
--
-- Уникальность сохраняют на уровне пары «группа + номер»: внутри
-- одной группы номер пишут один раз, между группами повтор разрешён.
--
-- Номера выборочного контроля к группе не привязаны (group_id IS
-- NULL), а в MySQL NULL в составном уникальном ключе не считается
-- равным NULL — без отдельного ключа SAMPLE перестал бы защищаться от
-- повтора. group_key сводит NULL к 0, как в uk_workshop_staff_binding.
-- ============================================================

ALTER TABLE input_control_card_cells
    ADD COLUMN group_key BIGINT
        GENERATED ALWAYS AS (IFNULL(group_id, 0)) STORED AFTER group_id;


ALTER TABLE input_control_card_cells
    DROP CONSTRAINT uk_input_control_cell,
    ADD CONSTRAINT uk_input_control_cell_per_group
        UNIQUE (card_id, cell_type, full_number, group_key);
