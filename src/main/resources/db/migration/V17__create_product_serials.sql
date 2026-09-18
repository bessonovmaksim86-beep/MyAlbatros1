-- ============================================================
-- Реестр серийных номеров изделий и связи между экземплярами
--
-- Номер изделия собирается из двух частей:
--
--     121200022  =  1212  +  00022
--                  ^^^^     ^^^^^
--                  код      5-значный порядковый номер
--                  классифи-  в пределах ТИПА изделия
--                  катора     (DEVICE / CELL / SENSOR / SYSTEM)
--
-- Поэтому серийный номер уникален в пределах типа изделия,
-- а не в пределах классификатора: один порядковый номер не может
-- быть выдан двум изделиям одного типа.
--
-- product_serials       — реестр выпущенных серийников;
-- production_item_links — связи конкретных экземпляров:
--                         ячейка ↔ прибор/датчик,
--                         система ↔ входящие датчики.
--
-- Одна таблица на все типы вместо четырёх отдельных: колонка
-- product_type и уникальный ключ (product_type, serial) дают ту же
-- изоляцию нумерации по типам, но поиск номера и поддержка остаются
-- простыми — не нужно перебирать четыре таблицы.
-- ============================================================


-- ============================================================
-- 1. Реестр серийных номеров
-- ============================================================

CREATE TABLE product_serials
(
    id                          BIGINT        NOT NULL AUTO_INCREMENT,

    /* DEVICE, CELL, SENSOR, SYSTEM — тип изделия по классификатору. */
    product_type                VARCHAR(20)   NOT NULL,

    /*
     * Порядковый номер в пределах типа. Хранится числом (00022 → 22):
     * пять знаков добавляются при выводе.
     */
    serial                      INT           NOT NULL,

    /* Изделие, для которого выдан номер (даёт 4-значный префикс). */
    product_classifier_id       BIGINT        NOT NULL,

    /* Заказ на производство, в рамках которого выдан номер. */
    issued_production_order_id  BIGINT        NULL,

    /* NEW, IN_WORK, DONE */
    status                      VARCHAR(20)   NOT NULL DEFAULT 'NEW',

    issued_at                   DATETIME(6)   NOT NULL,
    note                        VARCHAR(2000) NULL,

    CONSTRAINT pk_product_serials
        PRIMARY KEY (id),

    /* Серийник уникален в пределах типа изделия. */
    CONSTRAINT uk_product_serial_type_serial
        UNIQUE (product_type, serial),

    CONSTRAINT fk_product_serial_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_product_serial_production_order
        FOREIGN KEY (issued_production_order_id)
            REFERENCES production_orders (id),

    CONSTRAINT chk_product_serial_type
        CHECK (
            product_type IN ('DEVICE', 'CELL', 'SENSOR', 'SYSTEM')
        ),

    /* Нумерация пятизначная: 1..99999, вывод с дополнением нулями. */
    CONSTRAINT chk_product_serial_range
        CHECK (
            serial BETWEEN 1 AND 99999
        ),

    CONSTRAINT chk_product_serial_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'DONE')
        ),

    INDEX idx_product_serial_classifier
        (product_classifier_id),

    INDEX idx_product_serial_production_order
        (issued_production_order_id),

    INDEX idx_product_serial_status
        (status)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Позиция заказа на производство привязывается к серийнику
--
-- Колонка добавляется отдельно: таблица production_order_items
-- создана в V16, а реестр серийников — только здесь.
-- ============================================================

ALTER TABLE production_order_items
    ADD COLUMN serial_id BIGINT NULL
        AFTER customer_order_item_id;

ALTER TABLE production_order_items
    ADD CONSTRAINT fk_production_order_item_serial
        FOREIGN KEY (serial_id)
        REFERENCES product_serials (id);

ALTER TABLE production_order_items
    ADD INDEX idx_production_order_item_serial
        (serial_id);


-- ============================================================
-- 3. Связи между экземплярами изделий
--
-- Строка «родитель → потомок» для конкретных серийников:
--
--   CELL_TO_DEVICE   — ячейка собрана с прибором;
--   CELL_TO_SENSOR   — в ячейку установлен датчик;
--   SYSTEM_TO_CELL   — в систему входит ячейка;
--   SYSTEM_TO_DEVICE — в систему входит прибор;
--   SYSTEM_TO_SENSOR — в систему входит датчик.
-- ============================================================

CREATE TABLE production_item_links
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    parent_serial_id BIGINT        NOT NULL,
    child_serial_id  BIGINT        NOT NULL,

    /* CELL_TO_DEVICE, CELL_TO_SENSOR, SYSTEM_TO_CELL,
       SYSTEM_TO_DEVICE, SYSTEM_TO_SENSOR */
    link_type        VARCHAR(30)   NOT NULL,

    created_at       DATETIME(6)   NOT NULL,
    note             VARCHAR(2000) NULL,

    CONSTRAINT pk_production_item_links
        PRIMARY KEY (id),

    /* Одна и та же связь не заводится дважды. */
    CONSTRAINT uk_production_item_link
        UNIQUE (parent_serial_id, child_serial_id, link_type),

    CONSTRAINT fk_production_item_link_parent
        FOREIGN KEY (parent_serial_id)
            REFERENCES product_serials (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_production_item_link_child
        FOREIGN KEY (child_serial_id)
            REFERENCES product_serials (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_production_item_link_type
        CHECK (
            link_type IN (
                'CELL_TO_DEVICE',
                'CELL_TO_SENSOR',
                'SYSTEM_TO_CELL',
                'SYSTEM_TO_DEVICE',
                'SYSTEM_TO_SENSOR'
            )
        ),

    /* Экземпляр не связывается сам с собой. */
    CONSTRAINT chk_production_item_link_distinct
        CHECK (parent_serial_id <> child_serial_id),

    INDEX idx_production_item_link_child
        (child_serial_id),

    INDEX idx_production_item_link_type
        (link_type)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;