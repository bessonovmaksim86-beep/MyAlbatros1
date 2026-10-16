-- ============================================================
-- V58: Справочник контролируемых параметров с вариантами
-- ============================================================
-- Универсальная модель: параметр (например «Трубка») и список
-- его вариантов (например «1 слой(13мм.)», «2 слоя(13мм)»).
-- Используется технологом для управления параметрами операций.
-- ============================================================

CREATE TABLE control_params
(
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    name  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_control_params
        PRIMARY KEY (id),

    CONSTRAINT uk_control_params_name
        UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE TABLE control_param_values
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    control_param_id BIGINT       NOT NULL,
    value            VARCHAR(255) NOT NULL,

    CONSTRAINT pk_control_param_values
        PRIMARY KEY (id),

    CONSTRAINT fk_cp_values_param
        FOREIGN KEY (control_param_id)
            REFERENCES control_params (id)
            ON DELETE CASCADE,

    CONSTRAINT uk_cp_values_param_value
        UNIQUE (control_param_id, value),

    INDEX idx_cp_values_param
        (control_param_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
