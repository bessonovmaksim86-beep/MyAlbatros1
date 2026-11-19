-- ============================================================
-- V91 — Привязка ячеек к приборам при комплектовании групп ЗНП
--
-- При комплектовании группового ЗНП на приборы технолог заранее
-- задаёт в техпроцессе, какие виды ячеек нужны (контролируемые
-- параметры «Ячейка №»). Склад вносит номера ячеек, принятых на
-- склад, — они распределяются по видам, но пока не привязаны к
-- конкретным приборам.
--
-- Номера приборов генерируются автоматически при создании ЗНП
-- (через production_order_items). На экране комплектования
-- склад видит все номера приборов и вносит ячейки каждого вида.
--
-- После перехода ЗНП к следующей операции по техпроцессу (сдвиг
-- подразделению) исполнитель в определённой операции привязывает
-- каждую ячейку к конкретному прибору. После привязки путь ячейки
-- в производстве считается законченным — она «вошла в изделие».
-- ============================================================

CREATE TABLE cell_instrument_bindings
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,

    /*
     * Серийник прибора, которому принадлежит ячейка
     * (production_order_items.serial_id того же ЗНП).
     */
    instrument_serial_id BIGINT   NOT NULL,

    /* Номер ячейки, которая вошла в этот прибор. */
    cell_serial_id    BIGINT       NOT NULL,

    /*
     * Классификатор вида ячейки — тот самый, который технолог
     * задал в контролируемом параметре операции техпроцесса
     * (cell_classifier_id у TechProcessOperationControlParam).
     */
    cell_classifier_id BIGINT     NULL,

    /*
     * Заказ-комплектующий, в рамках которого выполнена привязка.
     */
    production_order_id BIGINT     NULL,

    /*
     * Операция, на которой выполнена привязка.
     */
    operation_id      BIGINT       NULL,

    /* Кто и когда привязал. */
    created_by        BIGINT       NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_cell_instrument_bindings
        PRIMARY KEY (id),

    CONSTRAINT fk_cib_instrument
        FOREIGN KEY (instrument_serial_id)
            REFERENCES product_serials (id),

    CONSTRAINT fk_cib_cell
        FOREIGN KEY (cell_serial_id)
            REFERENCES product_serials (id),

    CONSTRAINT fk_cib_classifier
        FOREIGN KEY (cell_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_cib_production_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id),

    CONSTRAINT fk_cib_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_cib_created_by
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    INDEX idx_cib_instrument
        (instrument_serial_id),

    INDEX idx_cib_cell
        (cell_serial_id),

    INDEX idx_cib_order
        (production_order_id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
