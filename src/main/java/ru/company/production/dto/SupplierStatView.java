package ru.company.production.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Поставщик в сводке склада.
 *
 * @param name            каноническое написание (из справочника,
 *                        иначе — как заведено в партиях)
 * @param note            примечание справочника
 * @param batchCount      партий от этого поставщика
 * @param cellCount       ячеек по всем партиям
 * @param receivedCells   ячеек в оприходованных партиях
 * @param pendingBatches  партий, не доведённых до сканирования
 * @param lastReceivedAt  дата последнего прихода, уже отформатированная
 * @param registered      есть ли поставщик в справочнике: строка без
 *                        записи — сигнал, что написание стоит зафиксировать
 */
public record SupplierStatView(
        String name,
        String note,
        long batchCount,
        long cellCount,
        long receivedCells,
        long pendingBatches,
        String lastReceivedAt,
        boolean registered
) {

    /* Диалект java8time в Thymeleaf не подключён — форматируем здесь. */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static String format(LocalDateTime value) {
        return value == null ? "—" : value.format(DATE_TIME_FORMATTER);
    }
}
