-- ============================================================
-- V90 — Журнал забракованных экземпляров плана
--
-- Когда заявка на отбраковку решается (решение бюро), ячейка
-- снимается с комплектования: строка матрицы serial_production_orders
-- удаляется, и весь порядок экземпляров съезжает — индексы теряются,
-- а экземпляр остаётся без номера в перечне работ (пустая строка).
--
-- Поэтому привязка «заказ + индекс экземпляра → забракованная позиция»
-- хранится отдельно от матрицы и переживает её сжатие: строка плана
-- обязана помнить, что изделие на этом экземпляре списано в брак, и
-- не вправе предлагать его проведение.
--
-- Номер ячейки и причину копируют на момент снятия: заявка тем временем
-- может быть решена иначе или удалена, а подпись экземпляра обязана
-- оставаться той, что видел цех.
-- ============================================================

CREATE TABLE scrapped_schedule_instances
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,

    /* Заказ-комплектующий, чей экземпляр списан в брак. */
    production_order_id BIGINT   NOT NULL,

    /*
     * Индекс экземпляра внутри заказа (0-based, см.
     * OrderScheduleRow.instanceIndex). Он и есть потерянная позиция:
     * матрица сжимается, а экземпляр плана остаётся.
     */
    instance_index INT          NOT NULL,

    /* Номер ячейки, которая стояла на этом экземпляре. */
    full_number    VARCHAR(20)  NOT NULL,

    /* Причина брака — читается в перечне работ вместо кнопки. */
    reason         VARCHAR(500) NOT NULL,

    /* Заявка, по которой экземпляр списан (может быть удалена). */
    defect_id      BIGINT       NULL,

    /* Кто и когда списал экземпляр: решение бюро либо отмена брака. */
    created_by     BIGINT       NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_scrapped_schedule_instances
        PRIMARY KEY (id),

    CONSTRAINT fk_ssi_production_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id),

    CONSTRAINT fk_ssi_defect
        FOREIGN KEY (defect_id)
            REFERENCES pending_defects (id),

    CONSTRAINT fk_ssi_created_by
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    INDEX idx_ssi_order
        (production_order_id),

    INDEX idx_ssi_defect
        (defect_id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
