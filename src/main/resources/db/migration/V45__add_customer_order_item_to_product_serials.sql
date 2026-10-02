-- ============================================================
-- V45: привязка серийных номеров к позиции заказа покупателя
--
-- Позволяет генерировать серийные номера при создании заказа
-- покупателя, а не только при создании выпуска.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN customer_order_item_id BIGINT NULL
        AFTER issued_production_order_id;

ALTER TABLE product_serials
    ADD CONSTRAINT fk_product_serial_customer_order_item
        FOREIGN KEY (customer_order_item_id)
        REFERENCES customer_order_items (id);
