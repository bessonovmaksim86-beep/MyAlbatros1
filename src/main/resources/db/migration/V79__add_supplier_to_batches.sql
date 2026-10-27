-- ============================================================
-- V79 -- Привязка партий прихода к поставщику справочника
--
-- Партии прихода содержат supplier_name как свободную строку.
-- Чтобы связать партию с заведённым поставщиком, добавляем
-- nullable-ссылку: приёмы, оформленные до этого изменения,
-- останутся без привязки, а новые будут обязательно привязаны
-- к справочнику.
-- ============================================================

ALTER TABLE cell_receiving_batches
    ADD COLUMN supplier_id BIGINT NULL
        AFTER supplier_name;

ALTER TABLE cell_receiving_batches
    ADD CONSTRAINT fk_cell_receiving_supplier
        FOREIGN KEY (supplier_id)
            REFERENCES warehouse_suppliers (id);

ALTER TABLE cell_receiving_batches
    ADD INDEX idx_cell_receiving_supplier (supplier_id);
