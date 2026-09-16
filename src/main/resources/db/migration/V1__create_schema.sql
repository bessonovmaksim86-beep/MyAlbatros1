-- ============================================================
-- Производственные службы
-- ============================================================

CREATE TABLE production_services
(
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    code   VARCHAR(20)  NOT NULL,
    name   VARCHAR(255) NOT NULL,
    active BOOLEAN      NOT NULL,

    CONSTRAINT pk_production_services
        PRIMARY KEY (id),

    CONSTRAINT uk_production_service_code
        UNIQUE (code),

    CONSTRAINT uk_production_service_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Организационные подразделения
-- ============================================================

CREATE TABLE organization_units
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    service_id BIGINT       NOT NULL,
    active     BOOLEAN      NOT NULL,

    CONSTRAINT pk_organization_units
        PRIMARY KEY (id),

    CONSTRAINT uk_organization_unit_code
        UNIQUE (code),

    CONSTRAINT uk_organization_unit_service_name
        UNIQUE (service_id, name),

    CONSTRAINT fk_organization_unit_service
        FOREIGN KEY (service_id)
            REFERENCES production_services (id),

    INDEX idx_organization_unit_service_active_name
        (service_id, active, name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- Каталог датчиков
-- ============================================================

CREATE TABLE sensor_catalog
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(255)  NOT NULL,
    search_parameters    VARCHAR(100)  NULL,
    protocol_search_path VARCHAR(1000) NULL,

    CONSTRAINT pk_sensor_catalog
        PRIMARY KEY (id),

    CONSTRAINT uk_sensor_catalog_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Классификаторы изделий
-- ============================================================

CREATE TABLE product_classifier
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    code              INT           NOT NULL,
    product_type      VARCHAR(20)   NOT NULL,
    product_name      VARCHAR(255)  NULL,
    sensor_catalog_id BIGINT        NULL,
    note              VARCHAR(2000) NULL,
    version           BIGINT        NULL,

    CONSTRAINT pk_product_classifier
        PRIMARY KEY (id),

    CONSTRAINT uk_product_classifier_code
        UNIQUE (code),

    CONSTRAINT chk_product_classifier_code
        CHECK (code BETWEEN 1000 AND 9999),

    CONSTRAINT fk_product_classifier_sensor_catalog
        FOREIGN KEY (sensor_catalog_id)
            REFERENCES sensor_catalog (id),

    INDEX idx_product_classifier_sensor_catalog
        (sensor_catalog_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Состав классификаторов
-- ============================================================

CREATE TABLE classifier_inclusion
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id       BIGINT      NOT NULL,
    target_id      BIGINT      NOT NULL,
    inclusion_mode VARCHAR(20) NOT NULL,
    `position`     INT         NOT NULL,

    CONSTRAINT pk_classifier_inclusion
        PRIMARY KEY (id),

    CONSTRAINT uk_classifier_inclusion_owner_target
        UNIQUE (owner_id, target_id),

    CONSTRAINT fk_classifier_inclusion_owner
        FOREIGN KEY (owner_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_classifier_inclusion_target
        FOREIGN KEY (target_id)
            REFERENCES product_classifier (id),

    INDEX idx_classifier_inclusion_target
        (target_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- Наименования операций
-- ============================================================

CREATE TABLE operation_names
(
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    code   VARCHAR(50)  NOT NULL,
    name   VARCHAR(255) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_operation_names
        PRIMARY KEY (id),

    CONSTRAINT uk_operation_name_code
        UNIQUE (code),

    CONSTRAINT uk_operation_name_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- Типы операций
-- ============================================================

CREATE TABLE operation_types
(
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    code   VARCHAR(50)  NOT NULL,
    name   VARCHAR(255) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_operation_types
        PRIMARY KEY (id),

    CONSTRAINT uk_operation_type_code
        UNIQUE (code),

    CONSTRAINT uk_operation_type_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
-- ============================================================
-- Роли пользователей
-- ============================================================

CREATE TABLE user_roles
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(50)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,

    CONSTRAINT pk_user_roles
        PRIMARY KEY (id),

    CONSTRAINT uk_user_role_code
        UNIQUE (code),

    CONSTRAINT uk_user_role_display_name
        UNIQUE (display_name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Операции
-- ============================================================

CREATE TABLE operations
(
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    operation_name_id     BIGINT      NOT NULL,
    operation_type_id     BIGINT      NOT NULL,
    executor_role_id      BIGINT      NOT NULL,
    service_id            BIGINT      NOT NULL,
    organization_unit_id  BIGINT      NULL,
    created_at            DATETIME(6) NOT NULL,

    organization_unit_key BIGINT
        GENERATED ALWAYS AS (
            IFNULL(organization_unit_id, 0)
        ) STORED,

    CONSTRAINT pk_operations
        PRIMARY KEY (id),

    CONSTRAINT fk_operation_name
        FOREIGN KEY (operation_name_id)
            REFERENCES operation_names (id),

    CONSTRAINT fk_operation_type
        FOREIGN KEY (operation_type_id)
            REFERENCES operation_types (id),

    CONSTRAINT fk_operation_executor_role
        FOREIGN KEY (executor_role_id)
            REFERENCES user_roles (id),

    CONSTRAINT fk_operation_service
        FOREIGN KEY (service_id)
            REFERENCES production_services (id),

    CONSTRAINT fk_operation_organization_unit
        FOREIGN KEY (organization_unit_id)
            REFERENCES organization_units (id),

    CONSTRAINT uk_operation_assignment
        UNIQUE (
            operation_name_id,
            operation_type_id,
            executor_role_id,
            service_id,
            organization_unit_key
        ),

    INDEX idx_operation_type
        (operation_type_id),

    INDEX idx_operation_executor_role
        (executor_role_id),

    INDEX idx_operation_service
        (service_id),

    INDEX idx_operation_organization_unit
        (organization_unit_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
-- ============================================================
-- Пользователи приложения
-- ============================================================

CREATE TABLE app_users
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    username             VARCHAR(100)  NOT NULL,
    full_name            VARCHAR(255)  NULL,
    specialty            VARCHAR(255)  NULL,
    service_id           BIGINT        NULL,
    organization_unit_id BIGINT        NULL,
    password_hash        VARCHAR(100)  NOT NULL,
    password_ciphertext  VARCHAR(1000) NULL,
    `role`               VARCHAR(50)   NOT NULL,
    active               BOOLEAN       NOT NULL,
    created_at           DATETIME(6)   NOT NULL,

    CONSTRAINT pk_app_users
        PRIMARY KEY (id),

    CONSTRAINT uk_app_user_username
        UNIQUE (username),

    CONSTRAINT fk_app_user_service
        FOREIGN KEY (service_id)
            REFERENCES production_services (id),

    CONSTRAINT fk_app_user_organization_unit
        FOREIGN KEY (organization_unit_id)
            REFERENCES organization_units (id),

    INDEX idx_app_user_service
        (service_id),

    INDEX idx_app_user_organization_unit
        (organization_unit_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;