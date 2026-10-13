-- ============================================================
-- КОМПЛЕКТОВАНИЕ РЕМОНТНОГО ЗНП ЯЧЕЙКАМИ ИЗ ИЗОЛЯТОРА БРАКА
--
-- Диспетчер заводит ЗНП на ремонт ячеек или подсборок (V37) и ведёт
-- его по техпроцессу, в котором есть комплектовочная операция бюро
-- входного контроля. БВК исполняет эту операцию так: открывает
-- изолятор брака с тем же фильтром, что и служба качества, отмечает
-- нужные ячейки и проводит их по операции — заказ закрывается не
-- «галочкой», а списком фактически взятых номеров.
--
-- Состав проводится отдельной таблицей, а не отметкой на заказе:
-- заказ описан изделием и количеством (у ремонтных видов номеров нет,
-- см. ZnpKind#isSerialBased), поэтому «что именно взяли» нужно хранить
-- построчно — иначе «Готово X из Y» пришлось бы считать по чужим
-- документам, а спор о составе остался бы неразрешимым.
--
-- Ячейка держится полным номером, а не ссылкой на product_serials —
-- по тому же мотиву, что и строка заказа на ремонт (V34): в изолятор
-- попадают и номера партий прихода, ещё не внесённых в реестр.
-- Ссылка на реестр была бы необязательной и не покрывала половину
-- состава изолятора.
--
-- Разновидность ячейки (cell_kind) копируется на момент проведения:
-- стадия движения (подсборка → ячейка) со временем меняется, а
-- требование «тип ячеек должен совпадать с видом заказа» проверяется
-- по тому, что было на момент комплектования.
-- ============================================================

CREATE TABLE repair_kitting_items
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,

    /* Ремонтный ЗНП, по которому проведено комплектование. */
    production_order_id BIGINT  NOT NULL,

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    full_number   VARCHAR(20)   NOT NULL,

    /* SUBASSEMBLY, CELL — разновидность на момент проведения. */
    cell_kind     VARCHAR(20)   NOT NULL,

    /*
     * Классификатор ячейки — наименование и код для отчёта.
     * NULL допустим: группа брака может быть заведена без привязки
     * к классификатору (номер введён вручную), заказ от этого не
     * перестаёт быть проведённым.
     */
    product_classifier_id BIGINT NULL,

    /* Автор и дата проведения — по ним судят, кто закрыл операцию. */
    kitted_by     BIGINT        NOT NULL,
    kitted_at     DATETIME(6)   NOT NULL,

    note          VARCHAR(500)  NULL,

    CONSTRAINT pk_repair_kitting_items
        PRIMARY KEY (id),

    /* Одну ячейку нельзя взять в один заказ дважды. */
    CONSTRAINT uk_repair_kitting_item_number
        UNIQUE (production_order_id, full_number),

    CONSTRAINT fk_repair_kitting_item_order
        FOREIGN KEY (production_order_id)
            REFERENCES production_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_repair_kitting_item_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_repair_kitting_item_user
        FOREIGN KEY (kitted_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_repair_kitting_item_kind
        CHECK (cell_kind IN ('SUBASSEMBLY', 'CELL'))
);

/* Состав конкретного заказа — основной путь чтения (счётчик «Готово»). */
CREATE INDEX idx_repair_kitting_item_order
    ON repair_kitting_items (production_order_id, kitted_at);

/* Поиск «попадала ли ячейка в ремонт» по номеру. */
CREATE INDEX idx_repair_kitting_item_number
    ON repair_kitting_items (full_number);


-- ============================================================
-- НОВОЕ СОБЫТИЕ ЖУРНАЛА: ЯЧЕЙКА ВЗЯТА В РЕМОНТНЫЙ ЗНП
--
-- У уза журнала своя колонка event_type со списком значений (V42), и
-- любое новое событие требует перечислить его заново — MySQL не
-- дополняет CHECK, а заменяет. Предыдущий список повторяется целиком,
-- иначе уже записанные события перестали бы проходить проверку.
--
-- Событие нужно изолятору и службе качества: сегодня по журналу видно,
-- что ячейка попала в брак и что ремонтный заказ отменён, но не видно,
-- что её реально взяли в работу — между «Ремонт принят» и исчезновением
-- из изолятора образовывалась дыра.
-- ============================================================

ALTER TABLE serial_movement_events
    DROP CHECK chk_sme_event_type;

ALTER TABLE serial_movement_events
    ADD CONSTRAINT chk_sme_event_type
        CHECK (
            event_type IN (
                'QUARANTINED',
                'RETURNED_TO_SUPPLIER',
                'REPAIR_ORDERED',
                'REPAIR_DONE',
                'REPAIR_CANCELLED',
                'IN_PRODUCTION',
                'ACCEPTED',
                'KITTED_TO_REPAIR_ZNP'
            )
        );
