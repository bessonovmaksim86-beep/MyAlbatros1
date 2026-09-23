-- ============================================================
-- ОБЛАСТЬ НУМЕРАЦИИ: КЛАССИФИКАТОР, А НЕ ТИП ИЗДЕЛИЯ
--
-- Изначально порядковый номер был уникален в пределах типа
-- (uk_product_serial_type_serial), и следующий номер искался по
-- всему типу. Правило ведения другое:
--
--   прибор, ячейка, система — нумерация своего классификатора:
--       888800122 → 888800123 (тот же классификатор, +1)
--
--   датчик — нумерация рода (sensor_catalog), сквозная между
--   классификаторами этого рода:
--       119200111 (ДУУ2М, классификатор 1192)
--       119400112 (ДУУ2М, классификатор 1194 — тот же род, +1)
--
-- Старый ключ прямо противоречил правилу: он запрещал двум
-- классификаторам одного типа иметь одинаковую серию, тогда как
-- теперь они обязаны продолжать общий ряд. Поэтому ключ заменяется
-- на уникальный по области нумерации.
--
-- Область нумерации — производное значение, поэтому она
-- материализована сгенерированным столбцом (тот же приём, что
-- operation_key в V23): ключ остаётся обычным UNIQUE, а не правилом,
-- которое приложение обязано помнить.
-- ============================================================


-- ============================================================
-- 1. Род датчика у серийного номера
--
-- Без него область нумерации датчика не выразить: сгенерированный
-- столбец не может обратиться к product_classifier, а JOIN в ключе
-- не бывает. Значение заполняется тем же родом, что у классификатора.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN sensor_catalog_id BIGINT NULL
        AFTER product_classifier_id;

ALTER TABLE product_serials
    ADD CONSTRAINT fk_product_serial_sensor_catalog
        FOREIGN KEY (sensor_catalog_id)
        REFERENCES sensor_catalog (id);

ALTER TABLE product_serials
    ADD INDEX idx_product_serial_sensor_catalog
        (sensor_catalog_id);

UPDATE product_serials serial
    JOIN product_classifier classifier
        ON classifier.id = serial.product_classifier_id
SET serial.sensor_catalog_id = classifier.sensor_catalog_id
WHERE serial.product_type = 'SENSOR';


-- ============================================================
-- 2. Область нумерации и новый уникальный ключ
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN numbering_key VARCHAR(32)
        GENERATED ALWAYS AS (
            CASE
                WHEN product_type = 'SENSOR'
                     AND sensor_catalog_id IS NOT NULL
                    THEN CONCAT('S', sensor_catalog_id)
                ELSE CONCAT('C', product_classifier_id)
            END
        ) STORED
        AFTER sensor_catalog_id;

/* Существующие номера новому ключу удовлетворяют: он слабее прежнего. */
ALTER TABLE product_serials
    DROP INDEX uk_product_serial_type_serial;

ALTER TABLE product_serials
    ADD CONSTRAINT uk_product_serial_numbering
        UNIQUE (numbering_key, serial);
