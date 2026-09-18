-- ============================================================
-- Приоритет как справочник с параметрами планирования
--
-- До этой миграции приоритет был перечислением (V13) и хранился
-- в customer_orders.priority строкой кода. Бизнес-смысл приоритета
-- сводился к одному — «насколько срочно», — чего для планирования
-- недостаточно. Теперь приоритет описывает правила запуска заказа
-- в работу:
--
--   start_days             «Запуск» — через сколько рабочих дней после
--                          планирования оформлять первые операции;
--   max_load_percent       «Допустимая загруженность» — процент
--                          загруженности участка, до которого ему ещё
--                          можно назначать работу;
--   transition_days        «Время межоперационных переходов» — рабочие
--                          дни паузы между окончанием операции и началом
--                          следующей;
--   max_manufacturing_days «Максимальная дата изготовления» — сколько
--                          рабочих дней остаётся на изготовление перед
--                          сроком отгрузки. При сроке 23.09 и значении 3
--                          минимальная дата изготовления — 20.09. Если
--                          начать заказ позже уже нельзя, планирование по
--                          такому приоритету отклоняется.
--
-- Значения прежнего перечисления сохраняются один-в-один (коды те же),
-- поэтому существующие заказы переживают миграцию без потери приоритета:
-- пустое значение переводится в ORDINARY — ровно так же, как это делал
-- CustomerOrderPriority.orDefault() в коде.
--
-- Колонка priority заменяется на priority_id: параметры живут в справочнике,
-- а заказ хранит только ссылку, иначе правка правила не подействовала бы
-- на уже заведённые заказы.
-- ============================================================


CREATE TABLE order_priorities
(
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    code                   VARCHAR(20) NOT NULL,
    name                   VARCHAR(100) NOT NULL,

    start_days             INT         NOT NULL,
    max_load_percent       INT         NOT NULL,
    transition_days        INT         NOT NULL,
    max_manufacturing_days INT         NOT NULL,

    badge_class            VARCHAR(40) NULL,
    active                 BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at             DATETIME(6) NOT NULL,

    CONSTRAINT pk_order_priorities
        PRIMARY KEY (id),

    CONSTRAINT uk_order_priority_code
        UNIQUE (code),

    CONSTRAINT uk_order_priority_name
        UNIQUE (name),

    CONSTRAINT chk_order_priority_start_days
        CHECK (start_days >= 0),

    CONSTRAINT chk_order_priority_max_load
        CHECK (max_load_percent BETWEEN 1 AND 100),

    CONSTRAINT chk_order_priority_transition_days
        CHECK (transition_days >= 0),

    CONSTRAINT chk_order_priority_max_manufacturing_days
        CHECK (max_manufacturing_days >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;


/*
 * Параметры подобраны по логике «чем срочнее, тем меньше паузы и
 * выше предельная загрузка»: очень высокий приоритет запускается
 * в тот же день и не считывается с загрузкой участка (100%),
 * очень низкий — самый долгий запуск и самая жёсткая отсечка.
 * badge_class переносит цвета прежнего перечисления, чтобы бейджи
 * в списках заказов не изменились.
 */
INSERT INTO order_priorities
    (code, name, start_days, max_load_percent, transition_days,
     max_manufacturing_days, badge_class, active, created_at)
VALUES
    ('VERY_HIGH', 'Очень высокий', 0, 100, 0, 1,
     'priority-critical', TRUE, NOW(6)),
    ('HIGH', 'Высокий', 1, 95, 1, 3,
     'priority-high', TRUE, NOW(6)),
    ('ORDINARY', 'Обычный', 3, 90, 2, 5,
     'priority-ordinary', TRUE, NOW(6)),
    ('LOW', 'Низкий', 5, 85, 3, 10,
     'priority-low', TRUE, NOW(6)),
    ('VERY_LOW', 'Очень низкий', 10, 80, 5, 15,
     'priority-minimal', TRUE, NOW(6));


-- ============================================================
-- Заказ покупателя: строка кода → ссылка на справочник
-- ============================================================

ALTER TABLE customer_orders
    ADD COLUMN priority_id BIGINT NULL AFTER priority;


/*
 * Перенос по коду: COALESCE закрывает и пустые значения, которые
 * код трактовал как «обычный». NOT NULL ставится только после
 * переноса — до него колонка физически не может быть обязательной.
 */
UPDATE customer_orders ord
    JOIN order_priorities prio
        ON prio.code = COALESCE(ord.priority, 'ORDINARY')
SET ord.priority_id = prio.id;

/*
 * Запасной перенос: код, заведённый в обход перечисления и не найденный
 * в справочнике, не должен блокировать миграцию — такой заказ получает
 * «обычный» приоритет, как это делал код при пустом значении.
 */
UPDATE customer_orders
SET priority_id = (SELECT id FROM order_priorities WHERE code = 'ORDINARY')
WHERE priority_id IS NULL;

ALTER TABLE customer_orders
    MODIFY COLUMN priority_id BIGINT NOT NULL;


/*
 * Ограничение и индекс по старой строковой колонке переезжают на
 * приоритет-ссылку. idx_customer_order_priority_status нужен и после
 * переезда: выборка «очередь заказов по приоритету внутри статуса»
 * остаётся частой.
 */
ALTER TABLE customer_orders
    DROP INDEX idx_customer_order_priority_status;

ALTER TABLE customer_orders
    DROP CHECK chk_customer_order_priority;

ALTER TABLE customer_orders
    DROP COLUMN priority;

ALTER TABLE customer_orders
    ADD CONSTRAINT fk_customer_order_priority
        FOREIGN KEY (priority_id)
            REFERENCES order_priorities (id);

ALTER TABLE customer_orders
    ADD INDEX idx_customer_order_priority_status
        (priority_id, status);
