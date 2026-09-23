-- ============================================================
-- ИЗОЛЯТОР БРАКА СЛУЖБЫ КАЧЕСТВА: ЗАКАЗЫ НА РЕМОНТ (ЗНП)
--
-- Руководитель службы качества разбирает изолятор брака не партиями,
-- а ячейками: отбирает их по разновидности (подсборка / ячейка),
-- классификатору и типу брака, отмечает нужные и оформляет на них
-- заказ — на ремонт ячеек, на ремонт подсборок или на выдачу
-- комплектации.
--
-- Заказы живут в отдельной таблице, а не в production_orders:
-- заказ на производство (ЗП/ЗНП диспетчера, V16 и V21) описывает
-- выпуск продукции под заказ покупателя и образует дерево «ЗП →
-- ЗНП». Заказ на ремонт описывает работу с браком: у него нет заказа
-- покупателя и состава-выпуска, зато есть поставщик и перечень
-- конкретных номеров ячеек. Смешивать их в одной таблице означало бы
-- либо обязать ремонт иметь заказчика, либо разрешить выпуску строки
-- без изделия — оба варианта ломают CHECK-ограничения V16/V21.
--
-- Ячейка хранится полным номером, а не ссылкой на product_serials:
-- в изолятор попадают и номера, зарезервированные партией прихода и
-- ещё не внесённые в реестр (отметка «вся партия» в группе брака).
-- Ссылка на реестр была бы необязательной и не покрывала половину
-- состава.
--
-- Поставщик пишется написанием (supplier_name), как в партиях прихода
-- (см. V28), а supplier_id заполняется, когда такое написание есть в
-- справочнике склада: ремонт могут заявить поставщику, которого склад
-- в справочник ещё не заводил.
--
-- Роль SK_GENERAL отдельной миграции НЕ требует: app_users.role —
-- VARCHAR(50) с @Enumerated(EnumType.STRING), значение справочника
-- перечислено в коде, CHECK на колонку не заведен.
-- ============================================================


-- ============================================================
-- 1. Заголовок заказа на ремонт
-- ============================================================

CREATE TABLE repair_orders
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,

    /* Номер заказа, например ЗНР-2026-001. */
    number        VARCHAR(50)   NOT NULL,

    /* CELL_REPAIR, SUBASSEMBLY_REPAIR, KITS_ISSUE */
    order_type    VARCHAR(30)   NOT NULL,

    /* NEW, IN_WORK, DONE, CANCELLED */
    status        VARCHAR(20)   NOT NULL DEFAULT 'NEW',

    /*
     * Поставщик заказа. Свободная строка, а не ссылка: написание
     * выбирают из справочника склада, но заказ на ремонт могут
     * выставить и тому, кого в справочнике нет.
     */
    supplier_name VARCHAR(255)  NOT NULL,

    /* Совпавшая запись справочника поставщиков — для сводки. */
    supplier_id   BIGINT        NULL,

    order_date    DATE          NOT NULL,
    note          VARCHAR(2000) NULL,

    created_by    BIGINT        NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NULL,
    version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_repair_orders
        PRIMARY KEY (id),

    CONSTRAINT uk_repair_order_number
        UNIQUE (number),

    CONSTRAINT fk_repair_order_supplier
        FOREIGN KEY (supplier_id)
            REFERENCES warehouse_suppliers (id),

    CONSTRAINT fk_repair_order_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_repair_order_type
        CHECK (
            order_type IN (
                'CELL_REPAIR',
                'SUBASSEMBLY_REPAIR',
                'KITS_ISSUE'
            )
        ),

    CONSTRAINT chk_repair_order_status
        CHECK (
            status IN ('NEW', 'IN_WORK', 'DONE', 'CANCELLED')
        ),

    INDEX idx_repair_order_status (status),
    INDEX idx_repair_order_type (order_type),
    INDEX idx_repair_order_supplier (supplier_id),
    INDEX idx_repair_order_date (order_date)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- 2. Состав заказа: отмеченные ячейки
--
-- Одна строка = одна ячейка. Номер пишется целиком (9 знаков),
-- как его видят в изоляторе и на этикетке.
-- ============================================================

CREATE TABLE repair_order_items
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    repair_order_id       BIGINT       NOT NULL,

    /* Группа брака-источник: по ней восстановлены причина и партия. */
    defect_group_id       BIGINT       NULL,

    /* Классификатор ячейки — наименование и код для отчёта. */
    product_classifier_id BIGINT       NULL,

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    full_number           VARCHAR(20)  NOT NULL,

    /* SUBASSEMBLY, CELL — разновидность на момент формирования. */
    cell_kind             VARCHAR(20)  NOT NULL,

    position              INT          NOT NULL DEFAULT 1,
    note                  VARCHAR(500) NULL,

    CONSTRAINT pk_repair_order_items
        PRIMARY KEY (id),

    /* Одну ячейку нельзя отметить в заказ дважды. */
    CONSTRAINT uk_repair_order_item_number
        UNIQUE (repair_order_id, full_number),

    CONSTRAINT fk_repair_order_item_order
        FOREIGN KEY (repair_order_id)
            REFERENCES repair_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_repair_order_item_group
        FOREIGN KEY (defect_group_id)
            REFERENCES input_control_defect_groups (id),

    CONSTRAINT fk_repair_order_item_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT chk_repair_order_item_kind
        CHECK (cell_kind IN ('SUBASSEMBLY', 'CELL')),

    INDEX idx_repair_order_item_order (repair_order_id, position),
    INDEX idx_repair_order_item_number (full_number)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
