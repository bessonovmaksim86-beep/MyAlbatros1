package ru.company.production.dto;

import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.WarehouseNaming;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Строка реестра наличия склада полуфабрикатов: одна позиция — одна
 * ячейка или подсборка.
 *
 * Строка держит подписи, а не ссылки на сущности: {@code open-in-view}
 * выключен, и обращение шаблона к классификатору или партии прервало
 * бы рендеринг. Номера и даты приводятся к виду печати здесь же, как
 * это делают {@code ProductSerial} и {@code CellReceivingBatch}.
 *
 * Размещение (свободна ли позиция и в какой ЗНП она оформлена)
 * приходит отдельными полями, а не вычисляется из номера: складывает
 * их сервис одним пакетным запросом к матрице комплектования, поэтому
 * строка обязана принять результат готовым, а не читать связи.
 */
public record WarehouseStockRow(
        Long serialId,
        Long classifierId,
        Integer classifierCode,
        String classifierName,
        String sensorClassName,
        ProductType productType,
        int serial,
        ProductSerialStatus status,
        String znpNumber,
        Long batchId,
        String supplierName,
        String incomingOrderNumber,
        String changeNumber,
        Integer batchQuantity,
        LocalDateTime batchAcceptedAt,
        LocalDateTime batchCellReadyAt,
        LocalDateTime acceptedAt,
        LocalDateTime cellReadyAt,
        LocalDateTime receivedAt,
        String placedZnpNumbers,
        boolean free
) {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Полный номер: код классификатора + пятизначная серия. */
    public String getFullNumber() {
        return (classifierCode == null ? "" : classifierCode)
                + String.format("%05d", serial);
    }

    /**
     * Разновидность позиции: готовая ячейка — подсборка.
     *
     * Граница задана отметкой cellReadyAt (V75): acceptedAt означает
     * «лежит на складе», но позиция остаётся подсборкой, пока не
     * отмечена готовой ячейкой. Тот же принцип, что у изолятора брака.
     */
    public QuarantineCellKind getKind() {
        return QuarantineCellKind.ofCellReady(cellReadyAt != null);
    }

    /** «Ячейка» / «Подсборка» — подпись колонки вида. */
    public String getKindLabel() {
        return getKind().getDisplayName();
    }

    /** Модификатор CSS-класса плашки вида. */
    public String getKindBadgeClass() {
        return getKind() == QuarantineCellKind.CELL ? "done" : "work";
    }

    /**
     * Изделие строкой: «5127 — Подсборка ЯПР56М».
     *
     * Термин «Подсборка» подставляется непринятым позициям тем же
     * способом, что и в перечнях прихода
     * ({@link WarehouseNaming#subassemblyLabel(String)}), — одна и та
     * же позиция обязана называться одинаково во всех разделах склада.
     */
    public String getCellLabel() {
        String name = displayName();

        if (getKind() == QuarantineCellKind.SUBASSEMBLY) {
            name = WarehouseNaming.subassemblyLabel(name);
        }

        if (classifierCode == null) {
            return name;
        }

        return name == null || name.isBlank()
                ? String.valueOf(classifierCode)
                : classifierCode + " — " + name;
    }

    /** Наименование изделия без кода и без термина склада. */
    public String getDisplayName() {
        return displayName();
    }

    /** Наименование для сортировки: пустое изделие уходит в конец списка. */
    public String getSortLabel() {
        String name = displayName();

        return name == null || name.isBlank() ? "Ячейка" : name;
    }

    /** Контрагент: у позиции без приходной партии его нет. */
    public String getSupplierLabel() {
        return supplierName == null || supplierName.isBlank()
                ? "—"
                : supplierName.trim();
    }

    /** Написание контрагента для группировки и сортировки. */
    public String getSupplierKey() {
        return supplierName == null || supplierName.isBlank()
                ? ""
                : supplierName.trim().toLowerCase(
                        java.util.Locale.forLanguageTag("ru")
                );
    }

    /** Приходный ордер партии. */
    public String getIncomingOrderLabel() {
        return incomingOrderNumber == null || incomingOrderNumber.isBlank()
                ? "—"
                : incomingOrderNumber;
    }

    /** Номер изменения партии. */
    public String getChangeLabel() {
        return changeNumber == null || changeNumber.isBlank()
                ? "—"
                : changeNumber;
    }

    /** Дата поступления — момент, которым позиция легла на склад. */
    public String getReceivedLabel() {
        return receivedAt == null ? "—" : receivedAt.format(DATE_FORMATTER);
    }

    /** Дата поступления с точностью до минуты — для колонки дат. */
    public String getReceivedDateTimeLabel() {
        return receivedAt == null ? "—" : receivedAt.format(DATE_TIME_FORMATTER);
    }

    /** Приёмку склада проводили. */
    public String getAcceptedLabel() {
        return acceptedAt == null ? "—" : acceptedAt.format(DATE_TIME_FORMATTER);
    }

    /** Позицию отметили готовой ячейкой (V75). */
    public String getCellReadyLabel() {
        return cellReadyAt == null ? "—" : cellReadyAt.format(DATE_TIME_FORMATTER);
    }

    /** Партию принял склад (отметка на партии), а не на позиции. */
    public String getBatchAcceptedLabel() {
        return batchAcceptedAt == null ? "—" : batchAcceptedAt.format(DATE_FORMATTER);
    }

    /** Партию приняли как готовые ячейки (V75). */
    public String getBatchCellReadyLabel() {
        return batchCellReadyAt == null ? "—" : batchCellReadyAt.format(DATE_FORMATTER);
    }

    /** Статус реестра для подписи колонки. */
    public String getStatusLabel() {
        return status == null ? "—" : status.getDisplayName();
    }

    /** Модификатор CSS-класса бейджа статуса реестра. */
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    /** ЗНП, вписанный в саму позицию (V50), без повтора с матрицей. */
    public String getZnpLabel() {
        return znpNumber == null || znpNumber.isBlank() ? null : znpNumber.trim();
    }

    /**
     * Размещение позиции: свободна она или оформлена в заказ.
     *
     * Свободная позиция лежит на полке и готова к выдаче; внесённая —
     * снята с полки действующим ЗНП (матрица комплектования V20), и
     * выдать её вторично нельзя.
     */
    public String getPlacementLabel() {
        if (free) {
            return "Свободна на складе";
        }

        return placedZnpNumbers == null || placedZnpNumbers.isBlank()
                ? "Оформлена в заказ"
                : "В заказе: " + placedZnpNumbers;
    }

    /** Модификатор CSS-класса плашки размещения. */
    public String getPlacementBadgeClass() {
        return free ? "done" : "work";
    }

    /** Момент поступления для сортировки: пустой уходит в конец. */
    public LocalDateTime receivedMoment() {
        return receivedAt;
    }

    /*
     * Двойники вида getX()/isX() держат рядом с аксессорами record
     * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению, а
     * record компилируется с аксессором <имя>() без префикса.
     */
    public boolean isFree() {
        return free;
    }

    public String getPlacedZnpNumbers() {
        return placedZnpNumbers;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    private String displayName() {
        if (productType == ProductType.SENSOR
                && sensorClassName != null
                && !sensorClassName.isBlank()) {
            return sensorClassName.trim();
        }

        return classifierName == null || classifierName.isBlank()
                ? null
                : classifierName.trim();
    }
}
