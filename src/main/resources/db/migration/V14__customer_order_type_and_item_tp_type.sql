-- ============================================================
-- Заказ покупателя: вид заказа и тип техпроцесса позиции
--
-- Вид заказа (order_type) задаёт назначение заказа:
--   PRODUCTION       — производство;
--   WARRANTY_REPAIR  — гарантийный ремонт;
--   PAID_REPAIR      — платный ремонт;
--   INTERNAL         — внутренний заказ.
-- Новый заказ по умолчанию создаётся как производственный,
-- поэтому колонка NOT NULL DEFAULT 'PRODUCTION'.
--
-- Тип техпроцесса позиции (tech_process_type) указывает, по какому
-- техпроцессу выпускается изделие. Пока значение одно —
--   BASIC — базовый техпроцесс, привязанный к изделию.
-- Значение по умолчанию 'BASIC', поэтому старые позиции получают
-- корректную трактовку без ручного заполнения.
-- ============================================================

ALTER TABLE customer_orders
    ADD COLUMN order_type VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION'
        AFTER status;

ALTER TABLE customer_orders
    ADD CONSTRAINT chk_customer_order_type
        CHECK (
            order_type IN (
                'PRODUCTION', 'WARRANTY_REPAIR',
                'PAID_REPAIR', 'INTERNAL'
            )
        );

/* Выборка заказов по виду внутри модуля диспетчера. */
ALTER TABLE customer_orders
    ADD INDEX idx_customer_order_type
        (order_type);


ALTER TABLE customer_order_items
    ADD COLUMN tech_process_type VARCHAR(20) NOT NULL DEFAULT 'BASIC'
        AFTER unit;

ALTER TABLE customer_order_items
    ADD CONSTRAINT chk_customer_order_item_tp_type
        CHECK (
            tech_process_type IN ('BASIC')
        );
