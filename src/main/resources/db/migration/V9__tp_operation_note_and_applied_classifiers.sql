-- ============================================================
-- 1. Примечание к операции внутри техпроцесса
--    (описание того, как именно операция выполняется в маршруте)
-- ============================================================

ALTER TABLE tech_process_operations
    ADD COLUMN note VARCHAR(2000) NULL;


-- ============================================================
-- 2. Изделия, к которым применён техпроцесс
--    Один маршрут может быть применён к нескольким изделиям:
--    «копирование» добавляет изделие к существующему техпроцессу,
--    а не создаёт новый.
-- ============================================================

CREATE TABLE tech_process_classifiers
(
    tech_process_id       BIGINT NOT NULL,
    product_classifier_id BIGINT NOT NULL,

    CONSTRAINT pk_tech_process_classifier
        PRIMARY KEY (
            tech_process_id,
            product_classifier_id
        ),

    CONSTRAINT fk_tech_process_classifier_process
        FOREIGN KEY (tech_process_id)
            REFERENCES tech_processes (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_tech_process_classifier_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    INDEX idx_tech_process_classifier_classifier
        (product_classifier_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 3. Существующие техпроцессы применяются к своему изделию
-- ============================================================

INSERT INTO tech_process_classifiers
    (tech_process_id, product_classifier_id)
SELECT process.id,
       process.product_classifier_id
FROM tech_processes process
WHERE process.product_classifier_id IS NOT NULL
ON DUPLICATE KEY UPDATE tech_process_id = VALUES(tech_process_id);
