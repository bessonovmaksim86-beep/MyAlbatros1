-- ============================================================
-- СПРАВОЧНИК ВЫХОДНЫХ И ПРАЗДНИЧНЫХ ДНЕЙ
--
-- Нерабочие дни — свойство производства, а не отдельного цеха:
-- 23 февраля и 9 мая совпадают у всех руководителей, поэтому
-- справочник ведёт администратор и распространяет отметки на все
-- подразделение разом. Раньше этот же день приходилось проставлять
-- каждому руководителю цеха в своём календаре, и первый же пропуск
-- ломал сводку по загрузке.
--
-- Отметки в employee_absences остаются как есть: справочник только
-- задаёт, КАКИЕ ДНИ считать нерабочими, а календари цехов продолжают
-- показывать уже проставленные DAY_OFF без изменений в своей логике.
-- ============================================================

CREATE TABLE holiday_days
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    holiday_date DATE         NOT NULL,
    kind         VARCHAR(20)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,

    CONSTRAINT pk_holiday_days
        PRIMARY KEY (id),

    /*
     * Один календарный день описан один раз: две записи на одну дату
     * дали бы два разных названия у одного дня в календарях цехов.
     */
    CONSTRAINT uk_holiday_day_date
        UNIQUE (holiday_date),

    CONSTRAINT chk_holiday_day_kind
        CHECK (kind IN ('HOLIDAY', 'DAY_OFF'))

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
