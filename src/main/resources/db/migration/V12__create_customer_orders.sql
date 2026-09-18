-- ============================================================
-- Заказы покупателя (модуль диспетчерского отдела)
--
-- Структура:
--   customers            — справочник покупателей;
--   customer_orders      — заголовок заказа покупателя;
--   customer_order_items — изделия, входящие в заказ.
--
-- Количество изделий в заказе не ограничено:
-- каждое изделие хранится отдельной строкой в customer_order_items,
-- поэтому в один заказ можно добавить любое число позиций.
--
-- На customer_orders.id можно ссылаться из других таблиц.
-- Например, будущие заказы на производство свяжутся с заказом
-- покупателя через:
--
--     ALTER TABLE production_orders
--         ADD COLUMN customer_order_id BIGINT NULL,
--         ADD CONSTRAINT fk_production_order_customer_order
--             FOREIGN KEY (customer_order_id)
--                 REFERENCES customer_orders (id);
--
-- Аналогично на позиции можно ссылаться по customer_order_items.id.
-- ============================================================


-- ============================================================
-- 1. Справочник покупателей
-- ============================================================

CREATE TABLE customers
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(50)  NOT NULL,
    name           VARCHAR(255) NOT NULL,
    contact_person VARCHAR(255) NULL,
    phone          VARCHAR(100) NULL,
    email          VARCHAR(255) NULL,
    note           VARCHAR(2000) NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)  NOT NULL,

    CONSTRAINT pk_customers
        PRIMARY KEY (id),

    CONSTRAINT uk_customer_code
        UNIQUE (code),

    CONSTRAINT uk_customer_name
        UNIQUE (name),

    INDEX idx_customer_active_name
        (active, name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Заказы покупателя (заголовок)
-- ============================================================

CREATE TABLE customer_orders
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    number          VARCHAR(50)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    order_date      DATE         NOT NULL,
    due_date        DATE         NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    contract_number VARCHAR(100) NULL,
    note            VARCHAR(2000) NULL,
    created_by      BIGINT       NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_customer_orders
        PRIMARY KEY (id),

    /* Номер заказа уникален: на него ссылаются пользователи
       и другие таблицы (заказы на производство). */
    CONSTRAINT uk_customer_order_number
        UNIQUE (number),

    CONSTRAINT fk_customer_order_customer
        FOREIGN KEY (customer_id)
            REFERENCES customers (id),

    CONSTRAINT fk_customer_order_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    /* Статусы: NEW, IN_WORK, PARTIALLY_DONE, DONE, CANCELLED */
    CONSTRAINT chk_customer_order_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'PARTIALLY_DONE', 'DONE', 'CANCELLED')
        ),

    CONSTRAINT chk_customer_order_dates
        CHECK (
            due_date IS NULL
            OR due_date >= order_date
        ),

    INDEX idx_customer_order_customer
        (customer_id),

    INDEX idx_customer_order_status_active
        (status, active),

    INDEX idx_customer_order_date
        (order_date),

    INDEX idx_customer_order_due_date
        (due_date)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 3. Изделия заказа покупателя
--
-- Одна строка = одно изделие в заказе.
-- Строки нумеруются по порядку (position), количество не ограничено.
--
-- Изделие выбирается из классификатора продукции (product_classifier):
-- допустимы любые типы — прибор, система, ячейка, датчик.
-- ============================================================

CREATE TABLE customer_order_items
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    order_id              BIGINT         NOT NULL,
    product_classifier_id BIGINT         NOT NULL,
    quantity              DECIMAL(15, 3) NOT NULL,
    unit                  VARCHAR(20)    NOT NULL DEFAULT 'PCS',
    done_quantity         DECIMAL(15, 3) NOT NULL DEFAULT 0,
    due_date              DATE           NULL,
    status                VARCHAR(20)    NOT NULL DEFAULT 'NEW',
    position              INT            NOT NULL,
    note                  VARCHAR(2000)  NULL,

    CONSTRAINT pk_customer_order_items
        PRIMARY KEY (id),

    /* Порядок изделий внутри заказа не повторяется. */
    CONSTRAINT uk_customer_order_item_position
        UNIQUE (order_id, position),

    CONSTRAINT fk_customer_order_item_order
        FOREIGN KEY (order_id)
            REFERENCES customer_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_customer_order_item_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    /* Статусы позиции: NEW, IN_WORK, PARTIALLY_DONE, DONE, CANCELLED */
    CONSTRAINT chk_customer_order_item_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'PARTIALLY_DONE', 'DONE', 'CANCELLED')
        ),

    CONSTRAINT chk_customer_order_item_quantity
        CHECK (quantity > 0),

    CONSTRAINT chk_customer_order_item_done_quantity
        CHECK (done_quantity >= 0),

    INDEX idx_customer_order_item_classifier
        (product_classifier_id),

    INDEX idx_customer_order_item_status
        (status)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
