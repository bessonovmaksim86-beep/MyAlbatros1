-- ============================================================
-- Заказы на производство (модуль диспетчерского отдела)
--
-- production_orders       — заголовок заказа на производство;
-- production_order_items  — выпускаемые изделия (позиции).
--
-- Заказ на производство создаёт диспетчер:
--   1) вводит номер заказа (ПЗ-ГГГГ-NNN, формируется автоматически);
--   2) выбирает операцию из справочника, только комплектовочные;
--   3) привязывает ОДИН заказ покупателя — он и определит состав
--      выпускаемых изделий.
--
-- Состав (production_order_items) формируется из позиций заказа
-- покупателя: сколько экземпляров заявлено в позиции, столько строк
-- выпуска появляется. Каждая строка получает серийный номер из
-- реестра product_serials (V17), где он хранится как 5-значный
-- порядковый номер в пределах типа изделия.
--
-- Серийный номер изделия:
--
--     121200022 = 1212 (код классификатора) + 00022 (серия)
--
-- Ссылка на позицию покупателя необязательна (NULL): заказ на
-- производство можно завести и без исходного заказа.
-- ============================================================


-- ============================================================
-- 1. Заголовок заказа на производство
-- ============================================================

CREATE TABLE production_orders
(
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    /* Номер заказа на производство, например ПЗ-2026-001. */
    number                  VARCHAR(50)   NOT NULL,

    /*
     * Заказ покупателя, ради которого выпущена продукция.
     * Один заказ покупателя обслуживает один заказ на производство,
     * поэтому колонка уникальна.
     */
    customer_order_id       BIGINT        NULL,

    /*
     * Операция из справочника operations — конкретное комплектовочное
     * задание, к которому привязан выпуск (тип операции KITTING).
     */
    production_operation_id BIGINT        NULL,

    order_date              DATE          NOT NULL,
    due_date                DATE          NULL,

    /* NEW, IN_WORK, PARTIALLY_DONE, DONE, CANCELLED */
    status                  VARCHAR(20)   NOT NULL DEFAULT 'NEW',

    note                    VARCHAR(2000) NULL,

    created_by              BIGINT        NULL,
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_production_orders
        PRIMARY KEY (id),

    CONSTRAINT uk_production_order_number
        UNIQUE (number),

    /* Один заказ покупателя — не более одного заказа на производство. */
    CONSTRAINT uk_production_order_customer_order
        UNIQUE (customer_order_id),

    CONSTRAINT fk_production_order_customer_order
        FOREIGN KEY (customer_order_id)
            REFERENCES customer_orders (id),

    CONSTRAINT fk_production_order_operation
        FOREIGN KEY (production_operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_production_order_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_production_order_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'PARTIALLY_DONE', 'DONE', 'CANCELLED')
        ),

    CONSTRAINT chk_production_order_dates
        CHECK (
            due_date IS NULL
            OR due_date >= order_date
        ),

    INDEX idx_production_order_status_active
        (status, active),

    INDEX idx_production_order_operation
        (production_operation_id),

    INDEX idx_production_order_date
        (order_date),

    INDEX idx_production_order_due_date
        (due_date)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Изделия заказа на производство
--
-- Одна строка = один выпускаемый экземпляр изделия.
-- Строки нумеруются по порядку (position).
--
-- Серийный номер (serial_id) добавляется в V17 — вместе с реестром
-- product_serials, на который он ссылается.
-- ============================================================

CREATE TABLE production_order_items
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    production_order_id   BIGINT        NOT NULL,

    /* Изделие классификатора — даёт 4-значный префикс номера. */
    product_classifier_id BIGINT        NOT NULL,

    /* Позиция заказа покупателя-источника (может отсутствовать). */
    customer_order_item_id BIGINT       NULL,

    position              INT           NOT NULL,

    /* NEW, IN_WORK, DONE */
    status                VARCHAR(20)   NOT NULL DEFAULT 'NEW',

    note                  VARCHAR(2000) NULL,

    CONSTRAINT pk_production_order_items
        PRIMARY KEY (id),

    /* Порядок изделий внутри заказа не повторяется. */
    CONSTRAINT uk_production_order_item_position
        UNIQUE (production_order_id, position),

    CONSTRAINT fk_production_order_item_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_production_order_item_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_production_order_item_customer_item
        FOREIGN KEY (customer_order_item_id)
            REFERENCES customer_order_items (id),

    CONSTRAINT chk_production_order_item_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'DONE')
        ),

    INDEX idx_production_order_item_classifier
        (product_classifier_id),

    INDEX idx_production_order_item_status
        (status)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;