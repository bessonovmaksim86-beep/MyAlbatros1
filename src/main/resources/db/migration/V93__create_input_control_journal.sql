-- ============================================================
-- ЖУРНАЛ ВХОДНОГО КОНТРОЛЯ КОМПЛЕКТУЮЩИХ
--
-- Несерийные комплектующие не проходят через партии ячеек
-- (CellReceivingBatch), поэтому для них — отдельный журнал,
-- который повторяет структуру бумажного листа и хранит принятые
-- и забракованные количества без движения по складу и изолятору.
-- ============================================================

CREATE TABLE input_control_components_journal
(
    /* Уникальный идентификатор записи. */
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    /* Дата проведения контроля (автоматическая). */
    control_date            DATETIME(6)   NOT NULL,

    /*
     * Вид контроля — всегда «INPUT» (подпись «Входной») для этого
     * журнала; значение перечисления JournalControlType, поэтому в
     * базе хранится имя константы, а не русская подпись.
     */
    control_type            VARCHAR(20)   NOT NULL DEFAULT 'INPUT',

    /* Наименование, марка, тип комплектующего — свободный текст. */
    product_name            VARCHAR(500)  NOT NULL,

    /*
     * Предприятие-поставщик: ссылка на справочник.
     * Если поставщик не найден, используется supplier_name.
     */
    supplier_id             BIGINT        NULL,

    /* Копия имени для записей без привязки к справочнику. */
    supplier_name           VARCHAR(255)  NULL,

    /* Номер сопроводительного документа. */
    doc_number              VARCHAR(100)  NULL,

    /* Дата сопроводительного документа. */
    doc_date                DATE          NULL,

    /* Общее количество по документу. */
    total_quantity          INT           NULL,

    /* Количество проверенной продукции. */
    checked_quantity        INT           NULL,

    /* Количество забракованной продукции. */
    scrap_quantity          INT           NULL,

    /* Номер акта о расхождении. */
    act_number              VARCHAR(100)  NULL,

    /* Дата акта о расхождении. */
    act_date                DATE          NULL,

    /* Описание несоответствия (+/−). */
    description             VARCHAR(1000) NULL,

    /* Принятые меры. */
    measures                VARCHAR(1000) NULL,

    /* Примечание. */
    note                    VARCHAR(2000) NULL,

    /* Контролёр, внёсший запись. */
    checked_by              BIGINT        NULL,

    /* Временные метки. */
    created_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)   NULL DEFAULT NULL,

    CONSTRAINT pk_input_control_journal
        PRIMARY KEY (id),

    CONSTRAINT fk_journal_supplier
        FOREIGN KEY (supplier_id)
            REFERENCES warehouse_suppliers (id),

    CONSTRAINT fk_journal_checker
        FOREIGN KEY (checked_by)
            REFERENCES app_users (id),

    INDEX idx_journal_date (control_date),
    INDEX idx_journal_supplier (supplier_id),
    INDEX idx_journal_product (product_name(100))

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
