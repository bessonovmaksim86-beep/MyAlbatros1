-- ============================================================
-- ОТМЕТКА ВХОДНОГО КОНТРОЛЯ НА ЯЧЕЙКЕ РЕЕСТРА
--
-- Ячейка партии проходит входной контроль, и это событие обязано
-- остаться на самой ячейке: «на любой стадии нужны отметки и
-- информация, как она проходила».
--
-- Колонку контроля заводят ОТДЕЛЬНО от product_serials.status.
-- status принадлежит производству (NEW / IN_WORK / DONE) и закреплён
-- CHECK chk_product_serial_status: вложить в него ещё и смысл
-- «прошла входной контроль» — значит дать одной колонке двух
-- хозяев, и производство будет затирать отметку контроля и наоборот.
-- Поэтому контроль живёт рядом, ничего не перезаписывая.
--
-- Отметка хранит всё, что нужно для прослеживаемости приёмки:
-- какой вид контроля проведён, когда и кем.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN input_control_type VARCHAR(20) NULL AFTER status;

ALTER TABLE product_serials
    ADD COLUMN input_control_at DATETIME(6) NULL AFTER input_control_type;

ALTER TABLE product_serials
    ADD COLUMN input_control_by BIGINT NULL AFTER input_control_at;

ALTER TABLE product_serials
    ADD CONSTRAINT fk_product_serial_input_control_by
        FOREIGN KEY (input_control_by)
            REFERENCES app_users (id);

/*
 * Вид контроля пустой, пока ячейку не проверяли. Заполненным он
 * бывает только одним из двух — иначе в отчёт попадёт значение,
 * которому никто не даст подпись.
 */
ALTER TABLE product_serials
    ADD CONSTRAINT chk_product_serial_input_control_type
        CHECK (
            input_control_type IS NULL
            OR input_control_type IN ('VISUAL', 'VISUAL_TECH')
        );

-- Отметку ищут по партии, когда пересобирают её проходной контроль.
ALTER TABLE product_serials
    ADD INDEX idx_product_serial_input_control (input_control_type);


-- ============================================================
-- ВИД КОНТРОЛЯ НА КАРТОЧКЕ ПРОВЕРКИ
--
-- Отметка на ячейке отвечает на вопрос «как проходила эта ячейка»,
-- карточка — «каким контролем закрыли партию». Итог читают в
-- закрытой карточке и в отчётах, поэтому вид хранят и на карточке:
-- восстанавливать его перебором ячеек партии дороже и точнее не
-- делает — партию отмечают одним видом целиком.
-- ============================================================

ALTER TABLE input_control_cards
    ADD COLUMN input_control_type VARCHAR(20) NULL AFTER sample_all;

ALTER TABLE input_control_cards
    ADD CONSTRAINT chk_input_control_card_control_type
        CHECK (
            input_control_type IS NULL
            OR input_control_type IN ('VISUAL', 'VISUAL_TECH')
        );
