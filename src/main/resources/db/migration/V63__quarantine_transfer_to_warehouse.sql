-- ============================================================
-- ПЕРЕМЕЩЕНИЕ ЯЧЕЕК ИЗ ИЗОЛЯТОРА БРАКА НА СКЛАД СПФ
--
-- Бюро входного контроля выбирает в изоляторе ячейки или подсборки,
-- указывает причину и перемещает их на приёмку складу
-- полуфабрикатов. Склад открывает отдельный раздел, сверяет номера
-- и принимает ячейки на склад либо возвращает обратно в изолятор.
--
-- Состав проводится отдельной таблицей, а не отметкой на ячейке
-- реестра — по тому же мотиву, что состав ремонтного ЗНП (V52) и
-- строка заказа на ремонт (V34): в изолятор попадают и номера
-- партий прихода, ещё не внесённые в product_serials, поэтому
-- ссылка на реестр не покрывала бы половину состава изолятора.
--
-- Причина перемещения обязательна: перемещённая ячейка перестаёт
-- быть видна в изоляторе, и без причины её исчезновение нельзя
-- было бы объяснить ни разбором брака, ни рекламацией поставщику.
--
-- Приёмку склада держат в тех же строках, а не второй таблицей:
-- перемещение и его приёмка — один документ в двух состояниях,
-- и раздельные таблицы заставили бы сверять их по номеру при каждом
-- чтении перечня.
-- ============================================================

CREATE TABLE quarantine_transfers
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    full_number   VARCHAR(20)   NOT NULL,

    /* SUBASSEMBLY, CELL — разновидность на момент перемещения. */
    cell_kind     VARCHAR(20)   NOT NULL,

    /*
     * Классификатор ячейки — наименование для перечня склада.
     * NULL допустим: группу брака заводят и без привязки к
     * классификатору (номер вводят вручную).
     */
    product_classifier_id BIGINT NULL,

    /* Партия прихода, из которой ячейка попала в изолятор. */
    receiving_batch_id BIGINT   NULL,

    /* Группа брака — по ней ячейку ищут обратно в изоляторе. */
    defect_group_id  BIGINT     NULL,

    /*
     * Причина перемещения, введённая контролёром БВК.
     * Обязательна на уровне сервиса: колонку оставляют nullable,
     * чтобы уже записанные строки не блокировали правку справочников.
     */
    reason        VARCHAR(500)  NOT NULL,

    /* Кто и когда переместил ячейку из изолятора. */
    transferred_by   BIGINT     NOT NULL,
    transferred_at   DATETIME(6) NOT NULL,

    /*
     * Приёмка складом: принята ли ячейка, кем и когда. Возврат в
     * изолятор отдельной отметки не требует — строку снимают, и
     * перечень изолятора показывает ячейку снова.
     */
    accepted         BOOLEAN       NOT NULL DEFAULT FALSE,
    accepted_by      BIGINT        NULL,
    accepted_at      DATETIME(6)   NULL,

    CONSTRAINT pk_quarantine_transfers
        PRIMARY KEY (id),

    /*
     * Ограничения уникальности по номеру нет намеренно: принятую на
     * склад ячейку тем временем снова бракуют, и она перемещается
     * вторично. Повтор перемещения до закрытия приёмки держит проверка
     * сервиса (findPendingFullNumbers), а не база — уникальной должна
     * быть незакрытая строка, а не номер вообще.
     */
    CONSTRAINT fk_quarantine_transfer_classifier
        FOREIGN KEY (product_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_quarantine_transfer_batch
        FOREIGN KEY (receiving_batch_id)
            REFERENCES cell_receiving_batches (id),

    CONSTRAINT fk_quarantine_transfer_group
        FOREIGN KEY (defect_group_id)
            REFERENCES input_control_defect_groups (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_quarantine_transfer_by
        FOREIGN KEY (transferred_by)
            REFERENCES app_users (id),

    CONSTRAINT fk_quarantine_transfer_accepted_by
        FOREIGN KEY (accepted_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_quarantine_transfer_kind
        CHECK (cell_kind IN ('SUBASSEMBLY', 'CELL'))
);

/* Незакрытые перемещения — основной путь чтения перечня склада. */
CREATE INDEX idx_quarantine_transfer_pending
    ON quarantine_transfers (accepted, transferred_at);

/* Поиск «перемещалась ли ячейка» по номеру. */
CREATE INDEX idx_quarantine_transfer_number
    ON quarantine_transfers (full_number);


-- ============================================================
-- НОВОЕ СОБЫТИЕ ЖУРНАЛА: ЯЧЕЙКА ПЕРЕДАНА СО СКЛАДА ИЗОЛЯТОРА
--
-- У журнала своя колонка event_type со списком значений (V42), и
-- любое новое событие требует перечислить его заново — MySQL не
-- дополняет CHECK, а заменяет. Предыдущий список повторяется
-- целиком, иначе уже записанные события перестали бы проходить
-- проверку.
--
-- Событие нужно обоим кабинетам: по журналу видно, что ячейка
-- покинула изолятор не в ремонт и не к поставщику, а ушла на
-- приёмку складу полуфабрикатов.
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
                'KITTED_TO_REPAIR_ZNP',
                'TRANSFERRED_TO_WAREHOUSE'
            )
        );
