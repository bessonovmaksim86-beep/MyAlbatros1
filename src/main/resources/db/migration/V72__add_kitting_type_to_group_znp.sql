-- ============================================================
-- V72 -- Тип комплектования групповых ЗНП
--
-- Групповые ЗНП на ячейки (GROUP_CELL) могут собираться из
-- подсборок. Тип kitting_type = 'SUBASSEMBLY' означает, что
-- заказ-комплектующий проходит через приёмку ячеек:
-- кладовщик вводит номера подсборок, которые дошли до склада,
-- и они становятся принятыми.
--
-- Поле nullable: обычные групповые ЗНП (без складского
-- комплектования) остаются с NULL.
-- ============================================================

ALTER TABLE production_orders
    ADD COLUMN kitting_type VARCHAR(30) NULL
        AFTER quantity;

ALTER TABLE production_orders
    ADD CONSTRAINT chk_production_order_kitting_type
        CHECK (kitting_type IN ('SUBASSEMBLY'));

CREATE INDEX idx_production_order_kitting_type
    ON production_orders (kitting_type);
