-- ============================================================
-- Заказы покупателя: ссылки на Б24, статусы и приоритет
--
-- Заказ покупателя ведётся параллельно с задачами в Битрикс24,
-- поэтому в заголовке хранятся внешние гиперссылки:
--   b24_order_url     — карточка заказа в Б24;
--   b24_checklist_url — чек-лист заказа в Б24.
--
-- Приоритет заказа (priority) нужен диспетчеру для очерёдности:
--   VERY_HIGH, HIGH, ORDINARY, LOW, VERY_LOW.
-- Пустое значение трактуется как «обычный», поэтому колонка NULL.
--
-- Набор статусов расширен производственными состояниями:
--   OVERDUED   — срок исполнения просрочен;
--   SUSPENDED  — работа по заказу временно остановлена;
--   DELETED    — заказ удалён (помечается, а не удаляется,
--                потому что на него ссылаются другие таблицы).
-- ============================================================

ALTER TABLE customer_orders
    ADD COLUMN b24_order_url     VARCHAR(500) NULL AFTER note,
    ADD COLUMN b24_checklist_url VARCHAR(500) NULL AFTER b24_order_url,
    ADD COLUMN priority          VARCHAR(20)  NULL AFTER b24_checklist_url;


/*
 * CHECK-ограничение статуса пересоздаётся: MySQL не позволяет
 * дополнять список значений на месте.
 */
ALTER TABLE customer_orders
    DROP CHECK chk_customer_order_status;

ALTER TABLE customer_orders
    ADD CONSTRAINT chk_customer_order_status
        CHECK (
            status IN (
                'NEW', 'IN_WORK', 'PARTIALLY_DONE', 'DONE',
                'OVERDUED', 'SUSPENDED', 'DELETED', 'CANCELLED'
            )
        );

ALTER TABLE customer_orders
    ADD CONSTRAINT chk_customer_order_priority
        CHECK (
            priority IS NULL
            OR priority IN (
                'VERY_HIGH', 'HIGH', 'ORDINARY', 'LOW', 'VERY_LOW'
            )
        );


/* Очередь заказов по приоритету внутри статуса. */
ALTER TABLE customer_orders
    ADD INDEX idx_customer_order_priority_status
        (priority, status);
