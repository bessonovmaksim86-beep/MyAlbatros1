-- ============================================================
-- Пометка «базовый» на техпроцессе для конкретного изделия
--
-- У изделия может быть несколько техпроцессов, и один маршрут
-- применяют к нескольким изделиям. Изделию нужен один основной —
-- базовый техпроцесс, по которому его выпускают по умолчанию, и
-- именно он должен стоять первым в любом списке маршрутов изделия.
--
-- Признак держат не у техпроцесса, а у пары «техпроцесс — изделие»:
-- один и тот же маршрут бывает базовым для одного изделия и обычным
-- (просто применённым) для другого.
--
-- Таблица связей была «чистой» таблицей ManyToMany с составным
-- ключом. Чтобы связь стала сущностью с признаком, ей дают
-- суррогатный id. Уникальность пары сохраняют отдельным ключом —
-- и делают это до снятия составного ключа: внешний ключ на
-- tech_process_id обязан всё время ALTER'а иметь поддерживающий
-- индекс, иначе MySQL запретит менять первичный ключ.
-- ============================================================

ALTER TABLE tech_process_classifiers
    ADD COLUMN basic BOOLEAN NOT NULL DEFAULT FALSE
        AFTER product_classifier_id;


ALTER TABLE tech_process_classifiers
    ADD CONSTRAINT uk_tech_process_classifier
        UNIQUE (tech_process_id, product_classifier_id);


ALTER TABLE tech_process_classifiers
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);


-- ============================================================
-- Существующие маршруты базовы для своих основных изделий:
-- product_classifier_id и раньше означал «изделие, для которого
-- разработан техпроцесс».
-- ============================================================

UPDATE tech_process_classifiers link
    JOIN tech_processes process
        ON process.id = link.tech_process_id
SET link.basic = TRUE
WHERE link.product_classifier_id = process.product_classifier_id;