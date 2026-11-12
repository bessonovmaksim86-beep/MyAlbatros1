-- ============================================================
-- V87 -- ПРОВЕДЕНИЕ ЦЕХОВОЙ ОПЕРАЦИИ — СОБЫТИЕ ПУТИ НОМЕРА
--
-- Журнал перемещений (V42/V65) описывал только ПЕРЕМЕЩЕНИЯ: приход,
-- выпуск, контроль, изолятор, ремонт, склад, передачу подразделению.
-- Работа цеха живёт в другом месте — строка графика закрывается
-- колонкой completed_at (V44), и в журнал не пишет ничего.
--
-- Следствие видно в паспорте изделия: номер «Принята подразделением»
-- (V81) — последняя строка хронологии, хотя цех уже распределил и
-- провёл монтаж, пайку и контроль. Номер при этом физически никуда не
-- переезжал, поэтому ни одно из существующих событий не подходило:
-- каждое из них означает смену места или состояния реестра, а не факт
-- сделанной работы.
--
-- Добавлен тип OPERATION_COMPLETED. Его пишет
-- SerialMovementJournalService#recordOperationCompleted из хука
-- проведения WorkshopDistributionService#completeRow — из единой точки,
-- как и все прочие события.
-- ============================================================


-- ============================================================
-- 1. НОВЫЙ ТИП СОБЫТИЯ
--
-- MySQL не дополняет CHECK, а заменяет его, поэтому перечень
-- повторяется целиком — иначе ранее записанные события перестали бы
-- проходить проверку.
-- ============================================================

ALTER TABLE serial_movement_events
    DROP CHECK chk_sme_event_type;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT chk_sme_event_type
        CHECK (
            event_type IN (
                'RECEIVED',
                'ISSUED',
                'INPUT_CONTROL_PASSED',
                'QUARANTINED',
                'RETURNED_TO_SUPPLIER',
                'REPAIR_ORDERED',
                'REPAIR_DONE',
                'REPAIR_CANCELLED',
                'KITTED_TO_REPAIR_ZNP',
                'IN_PRODUCTION',
                'ACCEPTED',
                'CELL_READY',
                'TRANSFERRED_TO_WAREHOUSE',
                'RETURNED_TO_QUARANTINE',
                'RELEASE_CANCELLED',
                'UNIT_TRANSFERRED',
                'UNIT_ACCEPTED',
                'UNIT_REJECTED',
                'OPERATION_COMPLETED'
            )
        );


-- ============================================================
-- 2. УЖЕ СДЕЛАННАЯ РАБОТА
--
-- Хук видит только будущие проведения, и без этого шага паспорт
-- 510200050 остался бы ровно с той же дырой, из-за которой правку и
-- начали: работы, закрытые до миграции, в хронологии не появились бы
-- никогда.
--
-- Экземпляр строки графика номера не хранит (V43 планирует изделие ДО
-- выпуска), поэтому номер восстанавливают теми же двумя правилами, что
-- читает раздел распределения, — другого источника в схеме нет:
--
--   * план заказа покупателя: экземпляр = строка состава выпуска той же
--     позиции заказа, пронумерованная по порядку выпуска
--     (WorkshopDistributionSerialService#resolve);
--   * групповой ЗНП: экземпляр = ячейка, внесённая складом в заказ,
--     i-я по внесению (V56, тот же порядок у
--     SubassemblyKittingService#completeScheduleRow).
--
-- Строка, у которой номер ещё не родился (выпуск не создан, склад не
-- внёс ячейку), пропускается: chk_sme_has_identifier требует хотя бы
-- один идентификатор, а выдумывать номер было бы хуже отсутствующей
-- строки.
--
-- Ссылку на заказ несёт только групповое основание: у строки плана
-- заказа покупателя колонка журнала означала бы «выпуск, выдавший
-- номер», тогда как работа делалась ради заказа покупателя —
-- подписать её чужим документом нельзя, поэтому основание остаётся в
-- тексте note, а заказ пустует.
--
-- Тип новый, поэтому таких строк в журнале до миграции не было вовсе и
-- проверка на повтор не нужна.
-- ============================================================

/* --- 2.1 План заказа покупателя: экземпляр = строка состава выпуска. */

INSERT INTO serial_movement_events
    (serial_id, full_number, event_type, occurred_at, actor_id,
     production_order_id, serial_status, cell_kind,
     product_classifier_id, receiving_batch_id, note)
SELECT
    serial.id,
    CONCAT(cl.code, LPAD(serial.serial, 5, '0')),
    'OPERATION_COMPLETED',
    row.completed_at,
    COALESCE(row.completed_by, row.executor_id),
    NULL,
    serial.status,
    CASE WHEN serial.cell_ready_at IS NOT NULL
             THEN 'CELL'
             ELSE 'SUBASSEMBLY'
    END,
    serial.product_classifier_id,
    serial.receiving_batch_id,
    name.name
FROM order_schedule_rows row
JOIN customer_order_items customer_item
    ON customer_item.order_id = row.order_id
   AND customer_item.position = row.item_position
JOIN (
    SELECT
        item.customer_order_item_id AS customer_order_item_id,
        item.serial_id              AS serial_id,
        ROW_NUMBER() OVER (
            PARTITION BY item.customer_order_item_id
            ORDER BY item.position, item.id
        )                           AS instance_index
    FROM production_order_items item
    WHERE item.serial_id IS NOT NULL
      AND item.customer_order_item_id IS NOT NULL
) release_instance
    ON release_instance.customer_order_item_id = customer_item.id
   AND release_instance.instance_index = row.instance_index
JOIN product_serials serial
    ON serial.id = release_instance.serial_id
JOIN product_classifier cl
    ON cl.id = serial.product_classifier_id
JOIN operations operation
    ON operation.id = row.operation_id
JOIN operation_names name
    ON name.id = operation.operation_name_id
WHERE row.completed_at IS NOT NULL
  AND row.order_id IS NOT NULL;

/* --- 2.2 Групповой ЗНП: экземпляр = i-я внесённая складом ячейка. */

INSERT INTO serial_movement_events
    (serial_id, full_number, event_type, occurred_at, actor_id,
     production_order_id, serial_status, cell_kind,
     product_classifier_id, receiving_batch_id, note)
SELECT
    serial.id,
    CONCAT(cl.code, LPAD(serial.serial, 5, '0')),
    'OPERATION_COMPLETED',
    row.completed_at,
    COALESCE(row.completed_by, row.executor_id),
    row.production_order_id,
    serial.status,
    CASE WHEN serial.cell_ready_at IS NOT NULL
             THEN 'CELL'
             ELSE 'SUBASSEMBLY'
    END,
    serial.product_classifier_id,
    serial.receiving_batch_id,
    name.name
FROM order_schedule_rows row
JOIN (
    SELECT
        link.production_order_id AS production_order_id,
        link.serial_id           AS serial_id,
        ROW_NUMBER() OVER (
            PARTITION BY link.production_order_id
            ORDER BY link.created_at, link.id
        )                        AS instance_index
    FROM serial_production_orders link
) attached_instance
    ON attached_instance.production_order_id = row.production_order_id
   AND attached_instance.instance_index = row.instance_index
JOIN product_serials serial
    ON serial.id = attached_instance.serial_id
JOIN product_classifier cl
    ON cl.id = serial.product_classifier_id
JOIN operations operation
    ON operation.id = row.operation_id
JOIN operation_names name
    ON name.id = operation.operation_name_id
WHERE row.completed_at IS NOT NULL
  AND row.production_order_id IS NOT NULL;
