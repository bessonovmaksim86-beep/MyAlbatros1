-- ============================================================
-- V74 -- Заявки на отбраковку ячейки/подсборки с согласованием БВК
--
-- Когда ячейку или подсборку, находящуюся в цеху/отделе, нужно
-- направить в изолятор брака, оператор БВК создаёт заявку.
-- Заявка попадает на согласование представителю БВК (BVK_MASTER),
-- который принимает решение:
--   - подтвердить перемещение в изолятор брака,
--   - отклонить брак и направить ячейку на склад полуфабрикатов.
--
-- current_location — вычисляемое поле, фиксируется на момент создания
-- заявки как строка (последнее событие перемещения). Не FK, потому
-- что это «снимок» местоположения, а не ссылка на сущность.
--
-- full_number — работает и для номеров из product_serials, и для
-- номеров прихода, ещё не внесённых в реестр (как в QuarantineTransfer).
-- ============================================================

CREATE TABLE pending_defects
(
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    /* Полный номер ячейки/подсборки: 4 цифры кода + 5 цифр серии. */
    full_number             VARCHAR(20)   NOT NULL,

    /* CELL / SUBASSEMBLY — на момент создания заявки. */
    cell_kind               VARCHAR(20)   NOT NULL,

    /* Статус реестра на момент создания заявки (V65). */
    serial_status           VARCHAR(20)   NULL,

    /* Изделие — для отображения и отбора. Ссылка снимается, если
       классификатор удалён: ON DELETE SET NULL. */
    product_classifier_id   BIGINT        NULL,

    /* Партия прихода — для отображения. ON DELETE SET NULL. */
    receiving_batch_id      BIGINT        NULL,

    /* Местоположение на момент создания: «Цех №3», «Склад СПФ» и т. п. */
    current_location        VARCHAR(200)  NOT NULL,

    /* Причина брака (строка, 500 символов). */
    defect_reason           VARCHAR(500)  NOT NULL,

    /* Этап отбраковки (100 символов). */
    defect_stage            VARCHAR(100)  NOT NULL,

    /* Кто затребовал отбраковку. */
    requested_by_id         BIGINT        NOT NULL,

    /* Дата/время создания заявки. */
    requested_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    /* Решение: переместить в изолятор или отклонить брак. NULL = ожидает. */
    decision                VARCHAR(30)   NULL,

    /* Кто принял решение. */
    approved_by_id          BIGINT        NULL,

    /* Когда принято решение. */
    approved_at             DATETIME(6)   NULL,

    CONSTRAINT pk_pending_defects
        PRIMARY KEY (id),

    /* Заявки: свежие сверху. */
    INDEX idx_pending_defect_requested_at
        (requested_at DESC),

    /* Статус для фильтра «ожидают / решённые». */
    INDEX idx_pending_defect_decision
        (decision),

    /* По номеру ищут для истории перемещений. */
    INDEX idx_pending_defect_full_number
        (full_number),

    CONSTRAINT chk_pending_defect_cell_kind
        CHECK (cell_kind IN ('CELL', 'SUBASSEMBLY')),

    CONSTRAINT chk_pending_defect_decision
        CHECK (decision IS NULL OR decision IN
               ('APPROVED_TO_QUARANTINE', 'REJECTED_TO_WH'))

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
