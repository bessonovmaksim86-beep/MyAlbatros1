-- ============================================================
-- График заказа покупателя (планирование по приоритету)
--
-- Назначение: расчёт «Планировать заказ» строит предварительный
-- график — по одной строке на каждую операцию техпроцесса каждого
-- экземпляра каждой позиции заказа. Диспетчер смотрит расчёт и
-- вносит его в план одной кнопкой; до этой строки в статусе DRAFT
-- ничего занятым в загрузке не считается.
--
-- ПОЧЕМУ ОТДЕЛЬНАЯ ТАБЛИЦА, А НЕ product_serial_operations (V19).
-- Заказ покупателя планируют ДО выпуска: серийных номеров на момент
-- расчёта ещё нет, а planned_date у цеховых операций существует только
-- у уже выпущенного экземпляра. График заказа поэтому хранится сам по
-- себе — по позиции и номеру экземпляра внутри позиции.
--
-- РАЗДЕЛЕНИЕ ПОЛНОМОЧИЙ.
-- Диспетчер считает ДАТЫ (порядок, переходы, загрузку, отсечку
-- изготовления). Руководитель подразделения затем распределяет эти
-- даты по РАБОЧИМ МЕСТАМ и исполнителям — для этого отдельные
-- колонки work_place_id / executor_id, и трогать даты он не вправе:
-- срок заказа меняется, а график пересчитывает диспетчер.
--
-- ЗАГРУЗКА. Источник занятого времени — строки CONFIRMED этой таблицы:
-- они же дают и загрузку подразделений по дням, и очередь, по которой
-- следующий заказ уже не влезет в допустимый процент.
-- ============================================================

CREATE TABLE order_schedule_rows
(
    id                BIGINT         NOT NULL AUTO_INCREMENT,

    /* Заказ покупателя, график которого рассчитан. */
    order_id          BIGINT         NOT NULL,

    /* Позиция заказа (customer_order_items.position). */
    item_position     INT            NOT NULL,

    /*
     * Номер экземпляра внутри позиции: позиция на 10 изделий даёт
     * 10 строк на каждую операцию, instance_index = 0..9.
     */
    instance_index    INT            NOT NULL,

    /* Маршрут, по которому считается операция. */
    tech_process_id   BIGINT         NOT NULL,

    /* Операция из справочника (та же, что пойдёт в матрицу выпуска). */
    operation_id      BIGINT         NOT NULL,

    /* Подразделение — хозяин операции: по нему мастер видит работы. */
    organization_unit_id BIGINT      NULL,

    /* Порядок операции в маршруте и дата плана. */
    seq               INT            NOT NULL,
    planned_date      DATE           NOT NULL,

    /* Трудоёмкость на экземпляр, человеко-часы. */
    labor_hours       DECIMAL(10, 3) NOT NULL,

    /*
     * Загрузка подразделения в этот день по итогам расчёта, проценты:
     * сохраняется для предпросмотра, чтобы диспетчер видел, почему
     * дата именно эта, а не следующая.
     */
    load_percent      INT            NULL,

    /* DRAFT — предварительный расчёт, CONFIRMED — внесён в план. */
    status            VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',

    /* Распределение по рабочим местам — зона руководителя цеха. */
    work_place_id     BIGINT         NULL,
    executor_id       BIGINT         NULL,
    assigned_by       BIGINT         NULL,
    assigned_at       DATETIME(6)    NULL,

    note              VARCHAR(2000)  NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NULL,

    CONSTRAINT pk_order_schedule_rows
        PRIMARY KEY (id),

    /* Один экземпляр позиции выполняет одну операцию маршрута один раз. */
    CONSTRAINT uk_osr_order_item_instance_operation
        UNIQUE (order_id, item_position, instance_index, operation_id),

    CONSTRAINT fk_osr_customer_order
        FOREIGN KEY (order_id)
            REFERENCES customer_orders (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_osr_tech_process
        FOREIGN KEY (tech_process_id)
            REFERENCES tech_processes (id),

    CONSTRAINT fk_osr_operation
        FOREIGN KEY (operation_id)
            REFERENCES operations (id),

    CONSTRAINT fk_osr_organization_unit
        FOREIGN KEY (organization_unit_id)
            REFERENCES organization_units (id),

    CONSTRAINT fk_osr_work_place
        FOREIGN KEY (work_place_id)
            REFERENCES work_places (id),

    CONSTRAINT fk_osr_executor
        FOREIGN KEY (executor_id)
            REFERENCES app_users (id),

    CONSTRAINT fk_osr_assigned_by
        FOREIGN KEY (assigned_by)
            REFERENCES app_users (id),

    CONSTRAINT chk_osr_status
        CHECK (status IN ('DRAFT', 'CONFIRMED')),

    CONSTRAINT chk_osr_instance
        CHECK (instance_index >= 0),

    CONSTRAINT chk_osr_labor
        CHECK (labor_hours >= 0),

    /* Дата плана обязана быть в пределах года, иначе расчёт улетел бы в бесконечность. */
    CONSTRAINT chk_osr_planned_range
        CHECK (planned_date BETWEEN '2020-01-01' AND '2100-12-31'),

    /* Расчёт загрузки по подразделению и дню — главный запрос планирования. */
    INDEX idx_osr_unit_date
        (organization_unit_id, planned_date, status),

    /* Список работ цеха у мастера. */
    INDEX idx_osr_order_status
        (order_id, status),

    INDEX idx_osr_work_place
        (work_place_id, planned_date)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
