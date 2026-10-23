-- ============================================================
-- V76 -- Состав систем по справочнику base.class_sensors (class='systems')
--
-- Источник (строки class_sensors с class='systems'):
--   колонка 4 — наименование системы;
--   колонка 5 — вторичный прибор, входящий в систему;
--   колонка 6 — роды датчиков, которые могут входить в систему
--               (перечислены через запятую).
--
-- Правила переноса:
--   * система ищется в product_classifier по code (SYSTEM);
--   * прибор — product_classifier (DEVICE), не более одного на
--     систему: quantity = 1, max_quantity = 1;
--   * датчик — sensor_catalog, т.е. РОД датчика, а не конкретное
--     исполнение (см. V11: sensor_catalog_id, а не classifier);
--     quantity = 1, потолок берётся из названия системы
--     («до 6 шт» → 6, «до 24 шт» → 24), иначе 10;
--   * и прибор, и датчики входят факультативно → OPTIONAL.
--
-- И прибор, и датчик связываются только если запись уже есть в БД:
-- неподтверждённые позиции (приборы А18, POST4 и исполнения
-- «исп. 0», датчики «р20», «СУР-16», служебная система 3333
-- «Без системы») отбрасываются на этапе JOIN, а не вставляют битые
-- ссылки. Состав, заведённый вручную через «Технологию», не дублируется.
--
-- Наименования приборов/датчиков в источнике краткие («А17», «ДПУ8»),
-- в классификаторе — полные («Контроллер А17», «Датчик положения
-- уровня ДПУ8»), поэтому поиск: точное совпадение, иначе окончание
-- строки после пробела.
-- ============================================================

-- ------------------------------------------------------------
-- Системы, которых нет в V68 (диапазон 3001—3033), но которые
-- есть в источнике. Заводятся по той же схеме наименований, иначе
-- их состав некуда привязать.
-- ------------------------------------------------------------

INSERT IGNORE INTO product_classifier (code, product_type, product_name, version) VALUES
(3034, 'SYSTEM', 'Система АТМ', 0),
(3035, 'SYSTEM', 'Система АТМ3', 0),
(3036, 'SYSTEM', 'Система АТМ3-01', 0),
(3037, 'SYSTEM', 'Система АТМ3-02', 0),
(3038, 'SYSTEM', 'Система АТМ3-03', 0);

-- ------------------------------------------------------------
-- Приборы систем (position = 1)
-- ------------------------------------------------------------

INSERT INTO system_components
    (system_id, sensor_catalog_id, component_classifier_id,
     quantity, max_quantity, inclusion_mode, unit, position)
SELECT
    sys.id,
    NULL,
    dev.id,
    1.000,
    1.000,
    'OPTIONAL',
    'PCS',
    1
FROM (
    SELECT 3015 AS code, 'БСД4'      AS device UNION ALL
    SELECT 3024, 'БСД5А'                     UNION ALL
    SELECT 3026, 'ГАММА-8МА'                 UNION ALL
    SELECT 3027, 'А17'                       UNION ALL
    SELECT 3034, 'ГАММА-8МА'                 UNION ALL
    SELECT 3025, 'БСД5Н'                     UNION ALL
    SELECT 3017, 'БСД5А'                     UNION ALL
    SELECT 3018, 'БСД5Н'                     UNION ALL
    SELECT 3019, 'БСД4'                      UNION ALL
    SELECT 3001, 'БСД5А'                     UNION ALL
    SELECT 3002, 'ГАММА-8МА'                 UNION ALL
    SELECT 3003, 'А17'                       UNION ALL
    SELECT 3005, 'БСД5А'                     UNION ALL
    SELECT 3006, 'БСД5Н'                     UNION ALL
    SELECT 3007, 'А17'                       UNION ALL
    SELECT 3008, 'А17'                       UNION ALL
    SELECT 3036, 'А17'                       UNION ALL
    SELECT 3037, 'А18'                       UNION ALL
    SELECT 3038, 'POST4'                     UNION ALL
    SELECT 3010, 'ПВС7'                      UNION ALL
    SELECT 3011, 'ПВС7-d'                    UNION ALL
    SELECT 3012, 'ПВС8'                      UNION ALL
    SELECT 3013, 'ПВС8-d'                    UNION ALL
    SELECT 3014, 'ПВС15'                     UNION ALL
    SELECT 3009, 'ПВС4'
) src
JOIN product_classifier sys
    ON sys.code = src.code
   AND sys.product_type = 'SYSTEM'
/* Прибор выбирается один: точное совпадение важнее окончания строки. */
JOIN product_classifier dev
    ON dev.id = (
        SELECT d.id
        FROM product_classifier d
        WHERE d.product_type = 'DEVICE'
          AND (   d.product_name COLLATE utf8mb4_unicode_ci = src.device
               OR d.product_name COLLATE utf8mb4_unicode_ci LIKE CONCAT('% ', src.device))
        ORDER BY (d.product_name COLLATE utf8mb4_unicode_ci = src.device) DESC,
                 d.id
        LIMIT 1
    )
WHERE NOT EXISTS (
    SELECT 1
    FROM system_components sc
    WHERE sc.system_id = sys.id
      AND sc.component_classifier_id = dev.id
);

-- ------------------------------------------------------------
-- Роды датчиков систем (position = 2..N)
--
-- max_quantity: 6 и 24 явно указаны в наименованиях систем
-- «ИТМ3 исп. 3 (до 6 шт)» и «ИТМ3 исп. 4 (до 24 шт)»,
-- для остальных источниковым правилом задан потолок 10.
-- ------------------------------------------------------------

INSERT INTO system_components
    (system_id, sensor_catalog_id, component_classifier_id,
     quantity, max_quantity, inclusion_mode, unit, position)
SELECT
    sys.id,
    cat.id,
    NULL,
    1.000,
    src.max_quantity,
    'OPTIONAL',
    'PCS',
    src.pos
FROM (
    /* ДУУ4МА */
    SELECT 3015 AS code, 'Датчик уровня ультразвуковой ДУУ2М' AS sensor, 10 AS max_quantity, 2 AS pos UNION ALL
    /* АТМ-2 исп.1 */
    SELECT 3024, 'Датчик уровня ультразвуковой ДУУ2М', 10, 2 UNION ALL
    SELECT 3024, 'Уровнемер поплавковый ДУУ10',        10, 3 UNION ALL
    SELECT 3024, 'Датчик температуры многоточечный ДТМ2',       10, 4 UNION ALL
    SELECT 3024, 'Измеритель температуры многоточечный ДТМ3',   10, 5 UNION ALL
    /* АТМ-2 исп.3 */
    SELECT 3026, 'Датчик уровня ультразвуковой ДУУ2М', 10, 2 UNION ALL
    SELECT 3026, 'Датчик температуры многоточечный ДТМ2',      10, 3 UNION ALL
    SELECT 3026, 'Уровнемер радиоволновый РДУ3',       10, 4 UNION ALL
    /* АТМ-2 исп.4 */
    SELECT 3027, 'Датчик уровня ультразвуковой ДУУ2М', 10, 2 UNION ALL
    SELECT 3027, 'Уровнемер поплавковый ДУУ10',        10, 3 UNION ALL
    SELECT 3027, 'Датчик температуры многоточечный ДТМ2',       10, 4 UNION ALL
    SELECT 3027, 'Измеритель температуры многоточечный ДТМ3',   10, 5 UNION ALL
    SELECT 3027, 'Уровнемер радиоволновый РДУ3',       10, 6 UNION ALL
    SELECT 3027, 'р20',                                10, 7 UNION ALL
    /* АТМ */
    SELECT 3034, 'Датчик уровня ультразвуковой ДУУ2М', 10, 2 UNION ALL
    SELECT 3034, 'Датчик температуры многоточечный ДТМ2',      10, 3 UNION ALL
    SELECT 3034, 'Уровнемер радиоволновый РДУ3',       10, 4 UNION ALL
    /* АТМ-2 исп.2 */
    SELECT 3025, 'Уровнемер поплавковый ДУУ10',        10, 2 UNION ALL
    SELECT 3025, 'Измеритель температуры многоточечный ДТМ3',  10, 3 UNION ALL
    SELECT 3025, 'Уровнемер поплавковый ДУУ11',        10, 4 UNION ALL
    SELECT 3025, 'р20',                                10, 5 UNION ALL
    /* УМ ДУУ10 исп. 0/1/2/3 */
    SELECT 3016, 'Уровнемер поплавковый ДУУ10',        10, 2 UNION ALL
    SELECT 3017, 'Уровнемер поплавковый ДУУ10',        10, 2 UNION ALL
    SELECT 3018, 'Уровнемер поплавковый ДУУ10',        10, 2 UNION ALL
    SELECT 3019, 'Уровнемер поплавковый ДУУ10',        10, 2 UNION ALL
    /* ИТМ2 исп. 0/1/2 */
    SELECT 3001, 'Датчик температуры многоточечный ДТМ2',      10, 2 UNION ALL
    SELECT 3002, 'Датчик температуры многоточечный ДТМ2',      10, 2 UNION ALL
    SELECT 3003, 'Датчик температуры многоточечный ДТМ2',      10, 2 UNION ALL
    /* ИТМ3 исп. 0/1/2 */
    SELECT 3004, 'Измеритель температуры многоточечный ДТМ3',  10, 2 UNION ALL
    SELECT 3005, 'Измеритель температуры многоточечный ДТМ3',  10, 2 UNION ALL
    SELECT 3006, 'Измеритель температуры многоточечный ДТМ3',  10, 2 UNION ALL
    /* ИТМ3 исп. 3 (до 6 шт) / исп. 4 (до 24 шт) */
    SELECT 3007, 'Измеритель температуры многоточечный ДТМ3',   6, 2 UNION ALL
    SELECT 3008, 'Измеритель температуры многоточечный ДТМ3',  24, 2 UNION ALL
    /* АТМ3, АТМ3-01, АТМ3-02, АТМ3-03 */
    SELECT 3035, 'Уровнемер поплавковый ДУУ11',        10, 2 UNION ALL
    SELECT 3035, 'Уровнемер радиоволновый РДУ3',       10, 3 UNION ALL
    SELECT 3035, 'р20',                                10, 4 UNION ALL
    SELECT 3036, 'Уровнемер поплавковый ДУУ11',        10, 2 UNION ALL
    SELECT 3036, 'Уровнемер радиоволновый РДУ3',       10, 3 UNION ALL
    SELECT 3036, 'р20',                                10, 4 UNION ALL
    SELECT 3037, 'Уровнемер поплавковый ДУУ11',        10, 2 UNION ALL
    SELECT 3037, 'Уровнемер радиоволновый РДУ3',       10, 3 UNION ALL
    SELECT 3037, 'р20',                                10, 4 UNION ALL
    SELECT 3038, 'Уровнемер поплавковый ДУУ11',        10, 2 UNION ALL
    SELECT 3038, 'Уровнемер радиоволновый РДУ3',       10, 3 UNION ALL
    SELECT 3038, 'ДПУ8',                               10, 4 UNION ALL
    SELECT 3038, 'р20',                                10, 5 UNION ALL
    /* АТР */
    SELECT 3029, 'Уровнемер поплавковый ДУУ11',        10, 2 UNION ALL
    /* СУР-7 исп. 0/1, СУР-8 исп. 0/1 */
    SELECT 3010, 'ДПУ7',                               10, 2 UNION ALL
    SELECT 3011, 'ДПУ7',                               10, 2 UNION ALL
    SELECT 3012, 'ДПУ8',                               10, 2 UNION ALL
    SELECT 3013, 'ДПУ8М',                              10, 2 UNION ALL
    /* СУР-15 */
    SELECT 3014, 'ДПУ15',                              10, 2 UNION ALL
    SELECT 3014, 'ДПУ15М',                             10, 3 UNION ALL
    /* СУР-5 */
    SELECT 3009, 'Датчик положения уровня ДПУ5А',      10, 2
) src
JOIN product_classifier sys
    ON sys.code = src.code
   AND sys.product_type = 'SYSTEM'
/* Род датчика выбирается один: точное совпадение важнее окончания строки. */
JOIN sensor_catalog cat
    ON cat.id = (
        SELECT c.id
        FROM sensor_catalog c
        WHERE    c.name COLLATE utf8mb4_unicode_ci = src.sensor
              OR c.name COLLATE utf8mb4_unicode_ci LIKE CONCAT('% ', src.sensor)
        ORDER BY (c.name COLLATE utf8mb4_unicode_ci = src.sensor) DESC,
                 c.id
        LIMIT 1
    )
WHERE NOT EXISTS (
    SELECT 1
    FROM system_components sc
    WHERE sc.system_id = sys.id
      AND sc.sensor_catalog_id = cat.id
);
