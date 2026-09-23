-- ============================================================
-- ГРУППЫ БРАКА КАРТОЧКИ ВХОДНОГО КОНТРОЛЯ
--
-- Раньше на карточку приходился один перечень брака, одна цепочка
-- причин и одно решение. На практике партию бракуют частями: часть
-- ячеек возвращают поставщику по одному дефекту, часть оставляют в
-- изоляторе брака по другому. Поэтому брак группируют: группа —
-- перечень ячеек, причина и решение, у которых один и тот же исход.
--
-- Решение переезжает из карточки в группу: «переместить в изолятор»
-- и «вернуть поставщику» теперь относятся к своим ячейкам, а не к
-- партии целиком.
-- ============================================================

CREATE TABLE input_control_defect_groups
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    card_id     BIGINT       NOT NULL,

    /*
     * Решение по ячейкам группы. NOT NULL: группа существует ровно
     * тогда, когда для её ячеек выбран исход, группа без решения
     * означала бы ячейку, зависшую между изолятором и поставщиком.
     */
    decision    VARCHAR(30)  NOT NULL,

    /*
     * Брак признан по всей партии. Отметка живёт в группе, а не в
     * карточке: «вся партия» — это решение конкретной группы, и
     * вторая группа при такой отметке теряет смысл.
     */
    defect_all  BOOLEAN      NOT NULL DEFAULT FALSE,

    /* Порядок групп на экране: контролёр перечисляет дефекты сверху вниз. */
    sort_order  INT          NOT NULL DEFAULT 1,

    created_at  DATETIME(6)  NOT NULL,

    CONSTRAINT pk_input_control_defect_groups
        PRIMARY KEY (id),

    CONSTRAINT fk_input_control_group_card
        FOREIGN KEY (card_id)
            REFERENCES input_control_cards (id),

    CONSTRAINT chk_input_control_group_decision
        CHECK (decision IN ('QUARANTINE', 'RETURN_TO_SUPPLIER')),

    INDEX idx_input_control_group_card (card_id, sort_order)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


/*
 * Ячейка выборочного контроля к группе не привязана — group_id
 * остаётся NULL. Уникальность номера по карточке (uk_input_control_cell)
 * сохранена намеренно: ячейка физически не может лежать в двух
 * изоляторах одновременно, и два разных решения об одной ячейке —
 * ошибка, а не вариант.
 */
ALTER TABLE input_control_card_cells
    ADD COLUMN group_id BIGINT NULL AFTER card_id,
    ADD CONSTRAINT fk_input_control_cell_group
        FOREIGN KEY (group_id)
        REFERENCES input_control_defect_groups (id),
    ADD INDEX idx_input_control_cell_group (group_id);


/*
 * Причина всегда принадлежит группе брака: без группы она не описывает
 * ни одного исхода. Данных в таблице нет, поэтому колонку заводят
 * сразу обязательной.
 */
ALTER TABLE input_control_card_reasons
    ADD COLUMN group_id BIGINT NOT NULL AFTER card_id,
    ADD CONSTRAINT fk_input_control_reason_group
        FOREIGN KEY (group_id)
        REFERENCES input_control_defect_groups (id),
    ADD INDEX idx_input_control_reason_group (group_id);

/*
 * Одна и та же причина может относиться к разным группам (два дефекта
 * «Механическое повреждение» на разных ячейках), поэтому ключ
 * «причина один раз на карточку» перестраивают на уровень группы.
 */
ALTER TABLE input_control_card_reasons
    DROP CONSTRAINT uk_input_control_card_reason,
    ADD CONSTRAINT uk_input_control_card_reason_per_group
        UNIQUE (card_id, group_id, reason_id);


-- ============================================================
-- РЕШЕНИЕ И «ВСЯ ПАРТИЯ» БОЛЬШЕ НЕ СВОЙСТВЕННЫ КАРТОЧКЕ
--
-- Обе колонки переехали в input_control_defect_groups. Оставлять их
-- нельзя: заполненная карточка показывала бы одно решение при
-- нескольких разных, и отчёт по изолятору брака разошёлся бы с тем,
-- что видел контролёр.
-- ============================================================

ALTER TABLE input_control_cards
    DROP CHECK chk_input_control_card_decision;

ALTER TABLE input_control_cards
    DROP COLUMN decision,
    DROP COLUMN defect_all;
