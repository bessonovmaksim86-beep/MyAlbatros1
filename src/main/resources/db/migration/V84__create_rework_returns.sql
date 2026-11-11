-- ============================================================
-- V84 -- Возвраты на доработку по решению начальника цеха
--
-- БТК возвращает изделие сигналом: проведение при этом остаётся на
-- месте, потому что границу переделки определяет не бюро, а цех.
-- Начальник цеха в разделе «Возврат на доработку» видит все операции
-- маршрута своего подразделения и отмечает те, что нужно переделать, —
-- снятие факта происходит ровно с них (см. ReworkReturnService).
--
-- Одна строка на каждую снятую строку графика, а не одна на экземпляр:
-- исполнитель обязан увидеть причину по своей собственной работе, и
-- закрытие переделки идёт ровно тем повторным проведением, что закрывает
-- строку плана.
--
-- type различает два маршрута возврата: INTERNAL — цех переделывает
-- сам, PREDECESSOR — изделие уходит назад по маршруту и свой этап цех
-- перестает считать пройденным.
--
-- История сохраняется: запись живёт и после закрытия (resolved_at),
-- поэтому повторная доработка того же экземпляра заводит новую строку,
-- а прежняя остаётся в истории — для анализа брака и возвратов.
-- ============================================================

CREATE TABLE rework_returns
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,

    /*
     * Строка графика, проведение с которой снято для переделки.
     *
     * ON DELETE CASCADE: снять строку плана может только отмена
     * планирования, и висящий возврат по исчезнувшей работе цеху
     * не показать.
     */
    schedule_row_id BIGINT        NOT NULL,

    /*
     * Причина возврата, введённая начальником цеха.
     *
     * Обязательна: работа уже проведена, и без указания, что именно
     * не так, исполнитель не понял бы, что переделывать.
     */
    reason          VARCHAR(500)  NOT NULL,

    /* Внутренняя переделка или возврат назад по маршруту. */
    return_type     VARCHAR(20)   NOT NULL,

    /*
     * Состояние переделки: OPEN — заведена, но ещё не взята,
     * IN_REWORK — снята для переделки, COMPLETED — проведена снова.
     */
    status          VARCHAR(20)   NOT NULL,

    /*
     * Распространить на последующие операции маршрута.
     *
     * Отмеченная работа тянет за собой проведённые строки того же
     * экземпляра, идущие после неё: переделанная работа не может
     * оставаться под закрытой.
     */
    cascade_to_following BOOLEAN   NOT NULL DEFAULT FALSE,

    /* Кто и когда вернул на доработку. */
    returned_by     BIGINT        NOT NULL,
    returned_at     DATETIME(6)   NOT NULL,

    /*
     * Переделка закрыта: строка проведена повторно.
     *
     * Пустое значение — открытая переделка, по ней цех видит
     * подсветку, а раздел — счётчик возвращённых.
     */
    resolved_at     DATETIME(6)   NULL,
    resolved_by     BIGINT        NULL,

    CONSTRAINT pk_rework_returns
        PRIMARY KEY (id),

    CONSTRAINT fk_rr_schedule_row
        FOREIGN KEY (schedule_row_id)
            REFERENCES order_schedule_rows (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_rr_returned_by
        FOREIGN KEY (returned_by)
            REFERENCES app_users (id),

    CONSTRAINT fk_rr_resolved_by
        FOREIGN KEY (resolved_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_rr_return_type
        CHECK (return_type IN ('INTERNAL', 'PREDECESSOR')),

    CONSTRAINT chk_rr_status
        CHECK (status IN ('OPEN', 'IN_REWORK', 'COMPLETED')),

    /* Открытые возвраты строки — подсветка переделки и её закрытие. */
    INDEX idx_rr_row_open
        (schedule_row_id, resolved_at),

    /* История и аналитика: возвраты по дате. */
    INDEX idx_rr_returned
        (returned_at)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
