-- ============================================================
-- V62: Режим значения параметра — список ячеек
-- ============================================================
-- Для параметров, в названии которых есть слово «ячейка»,
-- значение выбирают из классификатора входящих ячеек датчика.
-- Ячейку хранят как ссылку на ProductClassifier, а не на
-- ControlParamValue, поэтому нужен отдельный столбец.
--
-- Теперь в строке контроля есть три режима:
--   control_param_value_id — стандартный список значений;
--   cell_classifier_id     — список входящих ячеек;
--   text_value             — свободный ввод.
-- ============================================================

ALTER TABLE tech_process_operation_control_params
    ADD COLUMN cell_classifier_id BIGINT NULL
        COMMENT 'Ячейка, выбранная в режиме «входящие ячейки»',
    ADD CONSTRAINT fk_tp_op_control_param_cell
        FOREIGN KEY (cell_classifier_id)
            REFERENCES product_classifier (id);
