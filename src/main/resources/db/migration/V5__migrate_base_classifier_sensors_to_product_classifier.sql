-- Flyway: перенос классификаторов датчиков из базы base
-- Источник: base.classifier_sensors
-- Назначение: текущая база приложения, таблица product_classifier
--
-- Соответствие данных:
--   1: classifier_sensors.id        -> product_classifier.id и code
--   2: classifier_sensors.id_class  -> product_classifier.sensor_catalog_id
--   4: classifier_sensors.execution -> product_classifier.product_name
--
-- Обязательные поля новой модели:
--   product_type = 'SENSOR'
--   version      = 0
--
-- sensor_catalog_id определяется через base.class_sensors.name. Это необходимо,
-- потому что в base.class_sensors имеется дубликат наименования для id 6 и 13,
-- а в новой таблице действует UNIQUE(name).

INSERT INTO product_classifier
    (
        id,
        code,
        product_type,
        product_name,
        sensor_catalog_id,
        note,
        version
    )
SELECT
    legacy_classifier.id AS id,
    legacy_classifier.id AS code,
    'SENSOR' AS product_type,
    CONVERT(legacy_classifier.execution USING utf8mb4) AS product_name,
    catalog.id AS sensor_catalog_id,
    NULL AS note,
    0 AS version
FROM base.classifier_sensors legacy_classifier
JOIN base.class_sensors legacy_catalog
    ON legacy_catalog.id = legacy_classifier.id_class
JOIN sensor_catalog catalog
    ON catalog.name COLLATE utf8mb4_unicode_ci =
       CONVERT(legacy_catalog.name USING utf8mb4) COLLATE utf8mb4_unicode_ci
ORDER BY legacy_classifier.id;

-- AUTO_INCREMENT обычно корректируется MySQL автоматически после явной вставки id.
-- Команда ниже намеренно не используется, поскольку ALTER TABLE ... AUTO_INCREMENT
-- не поддерживает вычисляемое подзапросом значение в обычном SQL Flyway.
