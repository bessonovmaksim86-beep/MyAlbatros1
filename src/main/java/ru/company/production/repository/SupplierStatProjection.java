package ru.company.production.repository;

import java.time.LocalDateTime;

/**
 * Сводка приходов по написанию поставщика.
 *
 * Считается по строкам партий (cell_receiving_batches.supplier_name),
 * а не по справочнику: справочник лишь фиксирует написание, партии
 * связаны с ним текстом, и поставщик мог приходить раньше, чем его
 * занесли в список.
 *
 * Партия с confirmed_at целиком попала в реестр — сканирование вносит
 * все её номера сразу, поэтому оприходованные ячейки считаются по
 * этому же признаку, без обращения к product_serials.
 */
public interface SupplierStatProjection {

    /** Написание в нижнем регистре — признак группы. */
    String getSupplierKey();

    /** Написание из партии в исходном регистре — для подписи строки. */
    String getDisplayName();

    long getBatchCount();

    long getCellCount();

    long getReceivedCells();

    long getPendingBatches();

    LocalDateTime getLastReceivedAt();
}
