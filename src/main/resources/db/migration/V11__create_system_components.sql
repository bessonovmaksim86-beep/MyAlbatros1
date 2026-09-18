-- ============================================================
-- Состав системы (отдельно от состава прибора)
--
-- В систему входят:
--   * роды датчиков — ссылка на sensor_catalog (выбирается род
--     из каталога, а не конкретное исполнение датчика);
--   * приборы — ссылка на product_classifier (тип DEVICE).
--
-- Ровно одна из колонок sensor_catalog_id / component_classifier_id
-- заполнена. Для каждого компонента хранятся фактическое количество
-- (quantity), максимально допустимое в системе (max_quantity),
-- режим включения и единица измерения.
--
-- Состав прибора (DEVICE → CELL) по-прежнему живёт в classifier_inclusion.
-- ============================================================

CREATE TABLE system_components
(
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    system_id               BIGINT        NOT NULL,
    sensor_catalog_id       BIGINT        NULL,
    component_classifier_id BIGINT        NULL,

    quantity                DECIMAL(15, 3) NOT NULL,
    max_quantity            DECIMAL(15, 3) NULL,
    inclusion_mode          VARCHAR(20)   NOT NULL,
    unit                    VARCHAR(20)   NOT NULL,
    position                INT           NOT NULL,

    CONSTRAINT pk_system_components
        PRIMARY KEY (id),

    CONSTRAINT fk_system_components_system
        FOREIGN KEY (system_id)
            REFERENCES product_classifier (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_system_components_sensor_catalog
        FOREIGN KEY (sensor_catalog_id)
            REFERENCES sensor_catalog (id),

    CONSTRAINT fk_system_components_classifier
        FOREIGN KEY (component_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT chk_system_components_component
        CHECK (
            (sensor_catalog_id IS NOT NULL AND component_classifier_id IS NULL)
            OR
            (sensor_catalog_id IS NULL AND component_classifier_id IS NOT NULL)
        ),

    INDEX idx_system_components_system
        (system_id),

    INDEX idx_system_components_sensor_catalog
        (sensor_catalog_id),

    INDEX idx_system_components_classifier
        (component_classifier_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
