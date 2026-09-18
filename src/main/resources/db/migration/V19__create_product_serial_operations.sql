-- ============================================================
-- Выполнение цеховых операций конкретным изделием (экземпляром)
--
-- Назначение: вокруг неизменного серийного номера (product_serials,
-- V17) накапливается план и факт по каждой операции, ответственный
-- исполнитель и порядок операций. Это замена плоским таблицам вида
-- «одна колонка = одна операция», где число операций ограничивалось
-- числом колонок.
--
-- КЛЮЧЕВОЕ: serial_id — ссылка на экземпляр, номер НЕ меняется.
-- Всё остальное (даты, люди, переделки) — строки этой таблицы.
--
-- ЗАКАЗЫ НЕ ДУБЛИРУЮТСЯ.
-- Заказ покупателя (customer_orders) и заказ на производство
-- (production_orders) — РАЗНЫЕ сущности, и здесь их нет намеренно:
--
--   * заказ на производство уже привязан к конкретному номеру изделия
--     (production_order_items.serial_id, product_serials.issued_
--     production_order_id);
--   * конкретная комплектовочная операция уже хранится у самого заказа
--     (production_orders.production_operation_id);
--
-- поэтому выносить заказ или его операцию в эту таблицу нельзя —
-- появился бы второй источник правды. Заказ-основание получается
-- связью с product_serials.
--
-- ПЕРЕДЕЛКИ.
-- Одна операция у одного изделия может выполняться несколько раз
-- (возврат на доработку). Поэтому уникальный ключ держит номер
-- попытки: attempt = 1 первая, 2 и далее — переделки. Прежняя попытка
-- НЕ перезаписывается: она остаётся строкой со статусом REWORKED,
-- и история переделок живёт в этой же таблице.
-- ============================================================

CREATE TABLE product_serial_operations
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,

    /* Экземпляр изделия: 121205333 и т. п. Номер неизменный. */
    serial_id       BIGINT        NOT NULL,

    /* Цеховая операция из справочника operations. */
    operation_id    BIGINT        NOT NULL,

    /*
     * Номер попытки: 1 — первое выполнение, 2+ — переделка.
     * Актуальной считается строка с наибольшим attempt.
     */
    attempt         INT           NOT NULL DEFAULT 1,

    /* PLANNED, IN_WORK, DONE, REWORKED */
    status          VARCHAR(20)   NOT NULL DEFAULT 'PLANNED',

    /* План и факт одной строкой — не разъезжаются по разным таблицам. */
    planned_date    DATE          NULL,
    actual_date     DATE          NULL,

    /* Ответственный за операцию. */
    executor_id     BIGINT        NULL,

    /* Кто отметил фактическое выполнение. */
    performed_by    BIGINT        NULL,

    /* Порядок операции в матрице выпуска. */
    seq             INT           NOT NULL DEFAULT 0,

    note            VARCHAR(2000) NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NULL,

    CONSTRAINT pk_product_serial_operations
        PRIMARY KEY (id),

    /* Одна попытка одной операции у изделия встречается один раз. */
    CONSTRAINT uk_pso_serial_operation_attempt
        UNIQUE (serial_id, operation_id, attempt),

    CONSTRAINT fk_pso_serial
        FOREIGN KEY (serial_id)
            REFERENCES product_serials (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_pso_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_pso_executor
        FOREIGN KEY (executor_id)
            REFERENCES app_users (id),

    CONSTRAINT fk_pso_performed_by
        FOREIGN KEY (performed_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_pso_status
        CHECK (
            status IN ('PLANNED', 'IN_WORK', 'DONE', 'REWORKED')
        ),

    CONSTRAINT chk_pso_attempt
        CHECK (attempt >= 1),

    /* Переделанная попытка не может считаться выполненной. */
    CONSTRAINT chk_pso_reworked_has_no_actual
        CHECK (
            status <> 'REWORKED'
            OR actual_date IS NULL
        ),

    /* Факт раньше плана допустим только если плана не было. */
    CONSTRAINT chk_pso_dates
        CHECK (
            actual_date IS NULL
            OR planned_date IS NULL
            OR actual_date >= planned_date
        ),

    /* «Где висит операция X», «что не сделано по плану». */
    INDEX idx_pso_operation_actual
        (operation_id, actual_date),

    /* Загрузка исполнителя. */
    INDEX idx_pso_executor
        (executor_id),

    /* Построение матрицы по изделию. */
    INDEX idx_pso_serial_seq
        (serial_id, seq)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
