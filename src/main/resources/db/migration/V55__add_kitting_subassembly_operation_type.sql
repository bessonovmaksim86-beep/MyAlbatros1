-- ============================================================
-- Компонент V55: тип операции "Комплектование подсборок"
-- ============================================================
-- Тип операции, которой комплектуют подсборки групповые ЗНП.
-- Заключительный ЗНП (сборка из подсборок) заводит приёмку на
-- склад полуфабрикатов через операцию заказа (RECEIVING), а
-- групповые ЗНП (ячейки, приборы, ремонт) ведут по техпроцессу,
-- и комплектовочная операция живёт в маршруте — не в заказе.
--
-- Код KITTING_SUBASSEMBLY, подразделение SEMI_FINISHED_PRODUCT_WAREHOUSE
-- (SEMI_FINISHED_PRODUCT_WAREHOUSE).
--
-- Для групповых ЗНП раздел «Комплектование подсборок в ЗНП»
-- ищет операцию в техпроцессе:
--   EXISTS (SELECT 1 FROM tech_process_operations tpo
--           WHERE tpo.techProcess = ord.techProcess
--             AND tpo.operation.operationType.code = 'KITTING_SUBASSEMBLY'
--             AND tpo.operation.organizationUnit.code = 'SEMI_FINISHED_PRODUCT_WAREHOUSE')
--
-- Для комплектования ЗНП (вложенных в выпуск) ищет операцию заказа:
--   ord.productionOperation.operationType.code = 'RECEIVING'
--   AND ord.productionOperation.organizationUnit.code = 'SEMI_FINISHED_PRODUCT_WAREHOUSE'
-- ============================================================

INSERT INTO operation_types (code, name, active)
SELECT 'KITTING_SUBASSEMBLY', 'Комплектование подсборок', TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM operation_types WHERE code = 'KITTING_SUBASSEMBLY'
);
