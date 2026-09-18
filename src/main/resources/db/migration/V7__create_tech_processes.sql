-- ============================================================
-- Техпроцессы
-- ============================================================

CREATE TABLE tech_processes
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(50)   NOT NULL,
    product_classifier_id BIGINT       NOT NULL,
    note                 VARCHAR(2000) NULL,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_tech_processes
        PRIMARY KEY (id),

    CONSTRAINT uk_tech_process_code
        UNIQUE (code),

    CONSTRAINT fk_tech_process_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Операции техпроцесса
-- ============================================================

CREATE TABLE tech_process_operations
(
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    tech_process_id BIGINT       NOT NULL,
    operation_id  BIGINT         NOT NULL,
    position      INT            NOT NULL,
    labor_hours   DECIMAL(10, 3) NOT NULL,
    machine_hours DECIMAL(10, 3) NOT NULL,
    daily_limit   INT            NOT NULL,

    CONSTRAINT pk_tech_process_operations
        PRIMARY KEY (id),

    CONSTRAINT uk_tech_process_operation
        UNIQUE (tech_process_id, operation_id),

    CONSTRAINT fk_tp_operation_process
        FOREIGN KEY (tech_process_id)
            REFERENCES tech_processes (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tp_operation_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Зависимости между операциями техпроцесса (DAG):
-- successor выполняется только после predecessor
-- ============================================================

CREATE TABLE tech_process_operation_deps
(
    successor_id  BIGINT NOT NULL,
    predecessor_id BIGINT NOT NULL,

    CONSTRAINT pk_tech_process_operation_deps
        PRIMARY KEY (successor_id, predecessor_id),

    CONSTRAINT fk_tp_dep_successor
        FOREIGN KEY (successor_id)
            REFERENCES tech_process_operations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tp_dep_predecessor
        FOREIGN KEY (predecessor_id)
            REFERENCES tech_process_operations (id)
            ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
