-- ============================================================
-- БЮРО ВХОДНОГО КОНТРОЛЯ: КАРТОЧКА ПРОВЕРКИ ПАРТИИ
--
-- Склад оприходовал партию — до передачи в производство её обязан
-- проверить входной контроль. Основанием служит уже существующая
-- партия прихода (cell_receiving_batches): её реквизиты и есть
-- карточка оприходования, которую контролёр видит в начале.
--
-- Проверка выборочная: в карточку вносятся только те ячейки партии,
-- которые реально осмотрели, поэтому их набор — подмножество
-- номеров партии, а не весь диапазон.
--
-- Карточка одна на партию в работе: партию «берут в работу», и
-- пока проверка не закрыта, вторую карточку той же партии завести
-- нельзя (проверка на стороне сервиса — MySQL не умеет частичные
-- уникальные индексы).
-- ============================================================

CREATE TABLE input_control_cards
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    /* Проверяемая партия прихода склада. */
    batch_id            BIGINT        NOT NULL,

    /*
     * Ход проверки: «В работе» — карточка открыта и заполняется,
     * «Завершён» — решение по партии принято, карточка только для
     * чтения.
     */
    status              VARCHAR(20)   NOT NULL DEFAULT 'IN_WORK',

    /* Выборочный контроль проставлен по всей партии. */
    sample_all          BOOLEAN       NOT NULL DEFAULT FALSE,

    /* Брак признан по всей партии — отдельные номера не вводят. */
    defect_all          BOOLEAN       NOT NULL DEFAULT FALSE,

    /*
     * Решение по партии: изолятор брака или возврат поставщику.
     * Появляется только вместе с браком — партию без дефектов
     * возвращать некуда.
     */
    decision            VARCHAR(30)   NULL,

    checked_by          BIGINT        NULL,
    started_at          DATETIME(6)   NOT NULL,
    completed_at        DATETIME(6)   NULL,
    note                VARCHAR(2000) NULL,

    CONSTRAINT pk_input_control_cards
        PRIMARY KEY (id),

    CONSTRAINT fk_input_control_card_batch
        FOREIGN KEY (batch_id)
            REFERENCES cell_receiving_batches (id),

    CONSTRAINT fk_input_control_card_checker
        FOREIGN KEY (checked_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_input_control_card_status
        CHECK (status IN ('IN_WORK', 'COMPLETED')),

    CONSTRAINT chk_input_control_card_decision
        CHECK (decision IS NULL
               OR decision IN ('QUARANTINE', 'RETURN_TO_SUPPLIER')),

    INDEX idx_input_control_card_batch (batch_id),
    INDEX idx_input_control_card_status (status)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- НОМЕРА ЯЧЕЕК, ВНЕСЁННЫЕ В КАРТОЧКУ
--
-- Одна таблица на оба списка с признаком строки: выборочный контроль
-- (проверенные ячейки) и брак (номера, попавшие в изолятор).
--
-- Номер хранится строкой, а не ссылкой на product_serials: карточку
-- заполняют с этикетки или сканера, и сверка с партией идёт по
-- полному номеру. Ссылка на реестр связала бы проверку с записью,
-- которая может быть изменена производством независимо от контроля.
-- ============================================================

CREATE TABLE input_control_card_cells
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    card_id     BIGINT       NOT NULL,

    /* Полный номер ячейки: код классификатора + пятизначная серия. */
    full_number VARCHAR(20)  NOT NULL,

    /* SAMPLE — проверено выборочно, DEFECT — забраковано. */
    cell_type   VARCHAR(10)  NOT NULL,

    CONSTRAINT pk_input_control_card_cells
        PRIMARY KEY (id),

    CONSTRAINT fk_input_control_cell_card
        FOREIGN KEY (card_id)
            REFERENCES input_control_cards (id),

    /* Один номер не вносят в один список дважды. */
    CONSTRAINT uk_input_control_cell
        UNIQUE (card_id, cell_type, full_number),

    CONSTRAINT chk_input_control_cell_type
        CHECK (cell_type IN ('SAMPLE', 'DEFECT')),

    INDEX idx_input_control_cell_card_type (card_id, cell_type)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- ПРИЧИНЫ БРАКА
--
-- Список выбираемый, а не свободный ввод: по причинам позже считают
-- рекламации поставщику. Дерево плоское и открытое по глубине:
-- parent_id задаёт «следующую причину» для уже выбранной, поэтому
-- уточнение появляется только после выбора предыдущей ступени.
--
-- is_custom отмечает ступень «Другое»: у неё контролёр вводит
-- текст, и внесённое написание заводится в этот же справочник —
-- список причин пополняется по мере работы, а не остаётся тем, что
-- было заведено при запуске.
-- ============================================================

CREATE TABLE input_control_defect_reasons
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,

    name        VARCHAR(255)  NOT NULL,

    /* Ступень выше: NULL — причина первого уровня. */
    parent_id   BIGINT        NULL,

    /* Ступень «Другое»: требует текста и пополняет справочник. */
    is_custom   BOOLEAN       NOT NULL DEFAULT FALSE,

    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)   NOT NULL,
    created_by  BIGINT        NULL,

    CONSTRAINT pk_input_control_defect_reasons
        PRIMARY KEY (id),

    /*
     * Написание уникально в пределах своей ступени: «Трещина» и у
     * корпуса, и у разъёма — разные пункты дерева, а два одинаковых
     * пункта на одной ступени — дубль, который разъедает статистику.
     */
    CONSTRAINT uk_input_control_reason_per_level
        UNIQUE (parent_id, name),

    CONSTRAINT fk_input_control_reason_parent
        FOREIGN KEY (parent_id)
            REFERENCES input_control_defect_reasons (id),

    CONSTRAINT fk_input_control_reason_author
        FOREIGN KEY (created_by)
            REFERENCES app_users (id),

    INDEX idx_input_control_reason_parent (parent_id)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- ПРИЧИНЫ, ВЫБРАННЫЕ В КАРТОЧКЕ
--
-- Строка — один шаг цепочки, а не одна причина на карточку: дерево
-- означает, что партию бракуют и по группе («Механическое
-- повреждение»), и по уточнению («Скол корпуса»). Хранят оба шага,
-- иначе отчёт по группе пришлось бы собирать разбором текстов.
--
-- custom_text сохраняет то, что контролёр ввёл в ступени «Другое»:
-- запись в справочнике появляется по написанию, а в карточке
-- остаётся исходная формулировка проверки.
-- ============================================================

CREATE TABLE input_control_card_reasons
(
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    card_id      BIGINT        NOT NULL,
    reason_id    BIGINT        NOT NULL,

    /* Порядок ступени в цепочке: 1 — причина первого уровня. */
    level_number INT           NOT NULL DEFAULT 1,

    custom_text  VARCHAR(500)  NULL,

    CONSTRAINT pk_input_control_card_reasons
        PRIMARY KEY (id),

    CONSTRAINT fk_input_control_reason_card
        FOREIGN KEY (card_id)
            REFERENCES input_control_cards (id),

    CONSTRAINT fk_input_control_card_reason_ref
        FOREIGN KEY (reason_id)
            REFERENCES input_control_defect_reasons (id),

    CONSTRAINT uk_input_control_card_reason
        UNIQUE (card_id, reason_id),

    INDEX idx_input_control_card_reason_card (card_id, level_number)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


-- ============================================================
-- НАЧАЛЬНЫЕ ПРИЧИНЫ БРАКА
--
-- Минимальный набор, с которым список не пустой при первом же
-- входе: группы первого уровня и их уточнения. Перечень открыт —
-- контролёр дополняет его через ступень «Другое».
-- ============================================================

INSERT INTO input_control_defect_reasons
    (name, parent_id, is_custom, active, created_at)
VALUES
    ('Механическое повреждение', NULL, FALSE, TRUE, NOW(6)),
    ('Несоответствие чертежу', NULL, FALSE, TRUE, NOW(6)),
    ('Дефект покрытия', NULL, FALSE, TRUE, NOW(6)),
    ('Посторонние включения', NULL, FALSE, TRUE, NOW(6)),
    ('Нарушение комплектности', NULL, FALSE, TRUE, NOW(6)),
    ('Другое', NULL, TRUE, TRUE, NOW(6));

/* Уточнения первой группы. */
INSERT INTO input_control_defect_reasons
    (name, parent_id, is_custom, active, created_at)
SELECT t.name,
       (SELECT p.id
          FROM input_control_defect_reasons p
         WHERE p.name = 'Механическое повреждение'
           AND p.parent_id IS NULL),
       FALSE, TRUE, NOW(6)
FROM (
    SELECT 'Царапина' AS name
    UNION ALL SELECT 'Вмятина'
    UNION ALL SELECT 'Скол'
    UNION ALL SELECT 'Трещина'
    UNION ALL SELECT 'Деформация корпуса'
) t;

/* Уточнения второй группы. */
INSERT INTO input_control_defect_reasons
    (name, parent_id, is_custom, active, created_at)
SELECT t.name,
       (SELECT p.id
          FROM input_control_defect_reasons p
         WHERE p.name = 'Несоответствие чертежу'
           AND p.parent_id IS NULL),
       FALSE, TRUE, NOW(6)
FROM (
    SELECT 'Отклонение размеров' AS name
    UNION ALL SELECT 'Ошибка отверстий'
    UNION ALL SELECT 'Несоосность'
) t;

/* Уточнения третьей группы. */
INSERT INTO input_control_defect_reasons
    (name, parent_id, is_custom, active, created_at)
SELECT t.name,
       (SELECT p.id
          FROM input_control_defect_reasons p
         WHERE p.name = 'Дефект покрытия'
           AND p.parent_id IS NULL),
       FALSE, TRUE, NOW(6)
FROM (
    SELECT 'Отслоение' AS name
    UNION ALL SELECT 'Коррозия'
    UNION ALL SELECT 'Пропуск покрытия'
) t;

/* Уточнения группы комплектности. */
INSERT INTO input_control_defect_reasons
    (name, parent_id, is_custom, active, created_at)
SELECT t.name,
       (SELECT p.id
          FROM input_control_defect_reasons p
         WHERE p.name = 'Нарушение комплектности'
           AND p.parent_id IS NULL),
       FALSE, TRUE, NOW(6)
FROM (
    SELECT 'Отсутствует крепёж' AS name
    UNION ALL SELECT 'Отсутствует уплотнение'
) t;
