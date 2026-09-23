-- ============================================================
-- ПРИЁМКА ПОДСБОРКИ СО СКЛАДА ПОСЛЕ БВК
--
-- Партия проходит путь: склад оприходовал → БВК проверил → часть
-- ячеек забракована → прошедшие контроль ячейки возвращаются на
-- склад, и кладовщик принимает их отдельной операцией. Финальный
-- статус такой партии — «Подсборка принята на склад».
--
-- Приёмку заводят ОТДЕЛЬНЫМИ колонками, а не внутри
-- product_serials.status. status принадлежит производству
-- (NEW / IN_WORK / DONE) и закреплён CHECK chk_product_serial_status:
-- у колонки не может быть двух хозяев, иначе приёмка затирала бы
-- статус сборки и наоборот (тот же принцип, что у отметки входного
-- контроля в V32).
--
-- На партии приёмка — событие (дата, кто принял, сколько ячеек),
-- а не enum: статус партии выводится из наличия даты, как уже
-- сделано для confirmed_at и qr_printed_at.
-- ============================================================

ALTER TABLE cell_receiving_batches
    ADD COLUMN accepted_at DATETIME(6) NULL AFTER confirmed_at;

ALTER TABLE cell_receiving_batches
    ADD COLUMN accepted_by BIGINT NULL AFTER accepted_at;

/*
 * Принято ячеек: может быть меньше quantity, потому что часть
 * партии отсеяна на брак в БВК и на склад не возвращается.
 */
ALTER TABLE cell_receiving_batches
    ADD COLUMN accepted_quantity INT NULL AFTER accepted_by;

ALTER TABLE cell_receiving_batches
    ADD CONSTRAINT fk_cell_receiving_accepted_by
        FOREIGN KEY (accepted_by)
        REFERENCES app_users (id);

/* Партии, ожидающие приёмки с БВК, ищут по отсутствию отметки. */
ALTER TABLE cell_receiving_batches
    ADD INDEX idx_cell_receiving_accepted (accepted_at);


-- ============================================================
-- ОТМЕТКА ПРИЁМКИ НА ЯЧЕЙКЕ РЕЕСТРА
--
-- Ячейка обязана нести приёмку сама, а не только в партии: по ней
-- позже сверяют, что изделие реально лежит на складе полуфабрикатов.
-- Колонки стоят рядом с отметкой входного контроля — это следующий
-- шаг того же маршрута.
-- ============================================================

ALTER TABLE product_serials
    ADD COLUMN accepted_at DATETIME(6) NULL AFTER input_control_by;

ALTER TABLE product_serials
    ADD COLUMN accepted_by BIGINT NULL AFTER accepted_at;

ALTER TABLE product_serials
    ADD CONSTRAINT fk_product_serial_accepted_by
        FOREIGN KEY (accepted_by)
        REFERENCES app_users (id);

ALTER TABLE product_serials
    ADD INDEX idx_product_serial_accepted (accepted_at);
