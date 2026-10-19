-- ============================================================
-- V67: Факты контролируемых параметров при проведении операции
-- ============================================================
-- Технолог задаёт в маршруте набор контролируемых параметров
-- операции и их обязательность, но значение знает только
-- исполнитель: он вносит тип проволоки, номер ячейки или
-- подтверждает, что фактическая ячейка относится к требуемому
-- классификатору.
--
-- Строка маршрута (tech_process_operation_control_params) описывает
-- ТРЕБОВАНИЕ, эта таблица — ОТМЕТКУ исполнителя о проведённом
-- экземпляре. Требование живёт в маршруте и меняется технологом,
-- отметка обязана остаться неизменной: по ней восстанавливают, что
-- именно контролировали при закрытии работы. Поэтому здесь хранятся
-- обе ссылки классификатора ячейки:
--
--   cell_classifier_id    — какое соответствие требовал техпроцесс,
--   actual_classifier_id  — какому классификатору реально принадлежала
--                           ячейка экземпляра.
--
-- Основание строки — order_schedule_rows: проводится экземпляр, а не
-- операция, поэтому и отметка привязана к экземпляру. ON DELETE
-- CASCADE держит журнал вместе с планом: снятый с работы заказ не
-- оставляет сиротских отметок.
-- ============================================================

CREATE TABLE operation_control_params
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_row_id       BIGINT      NOT NULL,
    control_param_id      BIGINT      NOT NULL,
    control_param_value_id BIGINT     NULL,
    text_value            VARCHAR(255) NULL,
    cell_classifier_id    BIGINT      NULL,
    actual_classifier_id  BIGINT      NULL,
    mandatory             BOOLEAN     NOT NULL DEFAULT FALSE,
    completed_at          DATETIME(6) NULL,

    CONSTRAINT pk_operation_control_params
        PRIMARY KEY (id),

    CONSTRAINT fk_ocp_schedule_row
        FOREIGN KEY (schedule_row_id)
            REFERENCES order_schedule_rows (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_ocp_control_param
        FOREIGN KEY (control_param_id)
            REFERENCES control_params (id),

    /*
     * Вариант справочника принадлежит своему параметру, поэтому
     * внешний ключ на control_params здесь не нужен: он уже стоит у
     * control_param_values, а повторная ссылка разрешила бы пару
     * «параметр из одного справочника — значение из другого».
     */
    CONSTRAINT fk_ocp_control_param_value
        FOREIGN KEY (control_param_value_id)
            REFERENCES control_param_values (id),

    CONSTRAINT fk_ocp_cell_classifier
        FOREIGN KEY (cell_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_ocp_actual_classifier
        FOREIGN KEY (actual_classifier_id)
            REFERENCES product_classifier (id),

    /*
     * Отметка ставится один раз на экземпляр: повторное проведение
     * отсекается сервисом, а индекс сторожит и гонку двух сессий.
     */
    CONSTRAINT uk_ocp_row_param
        UNIQUE (schedule_row_id, control_param_id),

    INDEX idx_ocp_param
        (control_param_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
