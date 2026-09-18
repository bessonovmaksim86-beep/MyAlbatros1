-- ============================================================
-- Заказ на производство ↔ изделие: связь «многие ко многим»
--
-- УТОЧНЕНИЕ БИЗНЕСА (снято предположение V19):
--
--   * в ОДИН датчик может входить НЕСКОЛЬКО заказов на производство;
--   * ОДИН заказ на производство может входить НЕСКОЛЬКО датчиков —
--     комплектование выполняется на ГРУППУ датчиков.
--
-- V19 исходила из обратного («заказ уже привязан к номеру, поэтому
-- его некуда выносить») и потому не включила заказ в таблицу
-- операций. Это предположение неверно — исправляется здесь.
--
-- ЧЕМ ЭТО ОТЛИЧАЕТСЯ ОТ УЖЕ СУЩЕСТВУЮЩИХ СВЯЗЕЙ (не дублирование):
--
--   product_serials.issued_production_order_id
--       «КЕМ ВЫПУЩЕН номер». Факт однократный и неизменный:
--       серийник выдаётся один раз в рамках одного выпуска.
--       1:1 — остаётся как есть.
--
--   production_order_items.serial_id
--       «КАКОЕ ИЗДЕЛИЕ ПОЗИЦИЯ ЗАКАЗА ВЫПУСКАЕТ». Это позиция
--       выпуска со своей позицией (position) и своим статусом.
--
--   serial_production_orders  (новая)
--       «КАКИМИ ЗАКАЗАМИ ИЗДЕЛИЕ УКОМПЛЕКТОВАНО». Матрица
--       комплектования: датчик ⇄ группа заказов. Именно эта
--       семантика раньше была не выражена нигде.
--
-- Смешивать их нельзя: «выпущен одним» и «укомплектован несколькими»
-- — разные факты, а не одна связь, записанная дважды.
-- ============================================================


-- ============================================================
-- 1. Матрица комплектования: изделие ⇄ заказ на производство
-- ============================================================

CREATE TABLE serial_production_orders
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    /* Изделие (датчик, ячейка, прибор, система) по серийнику. */
    serial_id           BIGINT        NOT NULL,

    /* Заказ на производство, вошедший в изделие. */
    production_order_id BIGINT        NOT NULL,

    /*
     * Комплектовочная операция, в рамках которой заказ вошёл в
     * изделие. Как правило совпадает с
     * production_orders.production_operation_id, но хранится здесь,
     * потому что один и тот же заказ может войти в разные изделия
     * разными операциями (группы комплектуются поэтапно).
     */
    operation_id        BIGINT        NULL,

    created_at          DATETIME(6)   NOT NULL,
    created_by          BIGINT        NULL,
    note                VARCHAR(2000) NULL,

    CONSTRAINT pk_serial_production_orders
        PRIMARY KEY (id),

    /* Один заказ не заводится в одно изделие дважды. */
    CONSTRAINT uk_spo_serial_order
        UNIQUE (serial_id, production_order_id),

    CONSTRAINT fk_spo_serial
        FOREIGN KEY (serial_id)
            REFERENCES product_serials (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_spo_production_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id),

    CONSTRAINT fk_spo_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_spo_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    /* «Какие заказы вошли в датчик» — открытие карточки изделия. */
    INDEX idx_spo_serial
        (serial_id),

    /* «Какие датчики в группе заказа» — состав заказа. */
    INDEX idx_spo_order
        (production_order_id, serial_id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Перенос известных связей, чтобы матрица не осталась пустой
-- ============================================================

-- 2.1 Заказ, в котором выдан номер, безусловно в изделие входит.
INSERT INTO serial_production_orders
    (serial_id, production_order_id, created_at, note)
SELECT
    ps.id,
    ps.issued_production_order_id,
    ps.issued_at,
    'Перенос: заказ выдачи номера'
FROM product_serials ps
WHERE ps.issued_production_order_id IS NOT NULL;

-- 2.2 Позиции выпуска тоже фиксируются в матрице.
--     INSERT IGNORE: строка из п. 2.1 уже заняла уникальный ключ.
INSERT IGNORE INTO serial_production_orders
    (serial_id, production_order_id, operation_id, created_at, note)
SELECT
    poi.serial_id,
    poi.production_order_id,
    po.production_operation_id,
    po.order_date,
    'Перенос: позиция выпуска'
FROM production_order_items poi
         JOIN production_orders po
              ON po.id = poi.production_order_id
WHERE poi.serial_id IS NOT NULL;


-- ============================================================
-- 3. Операция по изделию выполняется В РАМКАХ конкретного заказа
--
-- Без этой колонки при M:N теряется смысл: если датчик укомплектован
-- по двум заказам, невозможно понять, к какому из них относится
-- запись о выполнении операции.
--
-- NULL — операция общая для изделия и ни к одному заказу не привязана
-- (например, входной контроль выпущенного узла).
-- ============================================================

ALTER TABLE product_serial_operations
    ADD COLUMN production_order_id BIGINT NULL
        AFTER serial_id;

ALTER TABLE product_serial_operations
    ADD CONSTRAINT fk_pso_production_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id);

-- Построение матрицы «заказ → операции по его изделиям».
ALTER TABLE product_serial_operations
    ADD INDEX idx_pso_serial_order
        (serial_id, production_order_id);
