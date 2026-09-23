-- ============================================================
-- ПОСТАВЩИКИ СКЛАДА ПОЛУФАБРИКАТОВ
--
-- Поставщик в партии прихода (cell_receiving_batches.supplier_name)
-- — свободная строка: склад оформляет приход с документа, и имя
-- вводится с него. Из-за этого один и тот же поставщик со временем
-- заводится в нескольких написаниях, и статистика по нему рассыпается.
--
-- Справочник нужен, чтобы написание фиксировалось один раз: оператор
-- выбирает имя из списка, а страница «Поставщики» показывает сводку
-- по партиям, ячейкам и незакрытым приходам.
--
-- Связи с партиями справочник не заводит: колонка supplier_name
-- осталась источником данных для статистики, иначе старые приходы
-- остались бы без поставщика, а согласованность написания пришлось
-- бы гарантировать миграцией по неиндексированному тексту.
-- ============================================================

CREATE TABLE warehouse_suppliers
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,

    /* Наименование: уникально без учёта регистра (utf8mb4_unicode_ci). */
    name        VARCHAR(255)  NOT NULL,

    note        VARCHAR(1000) NULL,

    created_at  DATETIME(6)   NOT NULL,
    created_by  BIGINT        NULL,

    CONSTRAINT pk_warehouse_suppliers
        PRIMARY KEY (id),

    CONSTRAINT uk_warehouse_suppliers_name
        UNIQUE (name),

    CONSTRAINT fk_warehouse_suppliers_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
