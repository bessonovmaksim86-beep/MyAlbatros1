-- Flyway: перенос справочника датчиков из базы base
-- Источник: base.class_sensors
-- Назначение: текущая база приложения, таблица sensor_catalog
--
-- Соответствие полей исходной таблицы:
--   3: class_sensors.name          -> sensor_catalog.name
--   4: class_sensors.search_elem   -> sensor_catalog.search_parameters
--   5: class_sensors.file_protocol -> sensor_catalog.protocol_search_path
--
-- ВАЖНО: в исходных данных имя "Датчик положения уровня ДПУ5А"
-- встречается дважды (id 6 и 13), а sensor_catalog.name уникален.
-- Поэтому строки объединяются по name. Связи во второй миграции
-- восстанавливаются по имени, а не прямым копированием id_class.

INSERT INTO sensor_catalog
    (name, search_parameters, protocol_search_path)
SELECT
    CONVERT(cs.name USING utf8mb4) AS name,
    CONVERT(MAX(NULLIF(cs.search_elem, '')) USING utf8mb4) AS search_parameters,
    CONVERT(MAX(NULLIF(cs.file_protocol, '')) USING utf8mb4) AS protocol_search_path
FROM base.class_sensors cs
GROUP BY cs.name
ORDER BY MIN(cs.id);
