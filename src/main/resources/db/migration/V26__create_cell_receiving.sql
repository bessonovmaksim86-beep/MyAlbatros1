-- ============================================================
-- ОПРИХОДОВАНИЕ ЯЧЕЕК, ПОСТУПИВШИХ НА СКЛАД ПОЛУФАБРИКАТОВ
--
-- Склад получает полуфабрикаты на изготовление ячеек и оформляет
-- приход: поставщик, приходный ордер, количество, ячейка по
-- классификатору, номер изменения и примечание. Набор этих данных
-- — партия: под неё выпускается непрерывная серия номеров ячеек.
--
-- Номера в реестр попадают только после того, как оператор
-- отсканировал каждую ячейку партии. Поэтому таблица партий
-- существует отдельно от product_serials: пока приход не закрыт,
-- в реестре нет ни одной ячейки.
--
-- Диапазон first_serial..last_serial резервируется в момент
-- формирования партии. Без резерва номера пришлось бы выдавать
-- заново при подтверждении, и между генерацией QR и сканированием
-- их мог бы занять заказ на производство.
--
-- Номер изменения хранится строкой: на практике он содержит буквы
-- и дефис («Изм. 3», «И-2/24»), числовое поле для него тесно.
-- ============================================================

CREATE TABLE cell_receiving_batches
(
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    /* Поставщик: свободная строка, а не справочник. */
    supplier_name           VARCHAR(255)  NOT NULL,

    /* Номер приходного ордера — обязателен для каждой партии. */
    incoming_order_number   VARCHAR(100)  NOT NULL,

    /* Номер изменения чертежа/изделия. */
    change_number           VARCHAR(50)   NOT NULL,

    /* Сколько ячеек в партии: равняется числу выпущенных номеров. */
    quantity                INT           NOT NULL,

    /* Классификатор ячейки: даёт 4-значный префикс номеров. */
    cell_classifier_id      BIGINT        NOT NULL,

    /*
     * Зарезервированный диапазон порядковых номеров: партия занимает
     * его целиком, даже когда сканирование ещё не завершено.
     */
    first_serial            INT           NOT NULL,
    last_serial             INT           NOT NULL,

    /*
     * Подтверждение сканирования: до него партия существует только
     * как черновик оператора и в реестр ячеек не попадает.
     */
    confirmed_at            DATETIME(6)   NULL,

    /* Когда номера партии переданы в программу печати QR. */
    qr_printed_at           DATETIME(6)   NULL,

    created_at              DATETIME(6)   NOT NULL,
    created_by              BIGINT        NULL,
    note                    VARCHAR(2000) NULL,

    CONSTRAINT pk_cell_receiving_batches
        PRIMARY KEY (id),

    CONSTRAINT fk_cell_receiving_classifier
        FOREIGN KEY (cell_classifier_id)
            REFERENCES product_classifier (id),

    CONSTRAINT fk_cell_receiving_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    /* Партия не больше ёмкости пятизначной нумерации реестра. */
    CONSTRAINT chk_cell_receiving_quantity
        CHECK (quantity BETWEEN 1 AND 99999),

    /* Диапазон непрерывен: last - first + 1 = количество. */
    CONSTRAINT chk_cell_receiving_range
        CHECK (last_serial - first_serial + 1 = quantity),

    INDEX idx_cell_receiving_created
        (created_at),

    INDEX idx_cell_receiving_classifier
        (cell_classifier_id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- Ячейки партии в общем реестре серийных номеров
--
-- Колонка добавляется отдельно: product_serials создана в V17, а
-- партии появляются только здесь. Для номеров, выпущенных заказом
-- на производство, значение остаётся NULL — приход склада и выпуск
-- производства различаются именно этим признаком.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN receiving_batch_id BIGINT NULL
        AFTER issued_production_order_id;

ALTER TABLE product_serials
    ADD CONSTRAINT fk_product_serial_receiving_batch
        FOREIGN KEY (receiving_batch_id)
        REFERENCES cell_receiving_batches (id);

ALTER TABLE product_serials
    ADD INDEX idx_product_serial_receiving_batch
        (receiving_batch_id);