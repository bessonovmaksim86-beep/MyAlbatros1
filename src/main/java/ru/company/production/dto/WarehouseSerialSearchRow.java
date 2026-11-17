package ru.company.production.dto;

import ru.company.production.entity.InputControlType;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Строка результата поиска серийного номера.
 *
 * Держит все подписи, необходимые для идентификации изделия по повреждённой
 * этикетке: полный номер, наименование, статус, история операций, отметки
 * контроля и приёмки.
 */
public record WarehouseSerialSearchRow(

        Long serialId,

        Integer classifierCode,

        String classifierName,

        String sensorClassName,

        ProductType productType,

        Integer serial,

        ProductSerialStatus status,

        String znpNumber,

        String incomingOrderNumber,

        String changeNumber,

        String supplierName,

        String productionOrderNumber,

        String productionOrderItem,

        String unitName,

        String inputControlLabel,

        LocalDateTime inputControlAt,

        String inputControlByName,

        LocalDateTime acceptedAt,

        String acceptedByName,

        LocalDateTime cellReadyAt,

        String cellReadyByName,

        LocalDateTime issuedAt,

        List<String> completedOperationNames
) {

    private static final java.time.format.DateTimeFormatter DATE_TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final java.time.format.DateTimeFormatter DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Полный номер: код классификатора + пятизначная серия. */
    public String getFullNumber() {
        return (classifierCode == null ? "" : classifierCode)
                + String.format("%05d", serial == null ? 0 : serial);
    }

    /** Наименование изделия. */
    public String getDisplayName() {
        if (productType == ProductType.SENSOR
                && sensorClassName != null
                && !sensorClassName.isBlank()) {
            return sensorClassName.trim();
        }
        return classifierName == null || classifierName.isBlank()
                ? "Изделие"
                : classifierName.trim();
    }

    /** Вид контроля. */
    public String getInputControlDisplayLabel() {
        return inputControlLabel == null
                ? "контроль не проведён"
                : inputControlLabel;
    }

    /** Дата входного контроля. */
    public String getFormattedInputControlAt() {
        return inputControlAt == null ? "—" : inputControlAt.format(DATE_TIME_FMT);
    }

    /** Контролёр. */
    public String getInputControlByName() {
        return inputControlByName == null ? "—" : inputControlByName;
    }

    /** Приёмка склада. */
    public String getFormattedAcceptedAt() {
        return acceptedAt == null ? "—" : acceptedAt.format(DATE_TIME_FMT);
    }

    /** Кладовщик, принявший на склад. */
    public String getAcceptedByName() {
        return acceptedByName == null ? "—" : acceptedByName;
    }

    /** Готовая ячейка. */
    public String getFormattedCellReadyAt() {
        return cellReadyAt == null ? "—" : cellReadyAt.format(DATE_TIME_FMT);
    }

    /** Кладовщик, отметивший ячейку. */
    public String getCellReadyByName() {
        return cellReadyByName == null ? "—" : cellReadyByName;
    }

    /** Дата выпуска. */
    public String getFormattedIssuedAt() {
        return issuedAt == null ? "—" : issuedAt.format(DATE_TIME_FMT);
    }

    /** Операции маршрута через " · ". */
    public String getCompletedOperationsLabel() {
        if (completedOperationNames == null || completedOperationNames.isEmpty()) {
            return "—";
        }
        return String.join(" · ", completedOperationNames);
    }

    /** Статус. */
    public String getStatusLabel() {
        return status == null ? "—" : status.getDisplayName();
    }

    /** Бейдж статуса. */
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Дублирующие геттеры для Thymeleaf: record-аксессоры вида
     * <имя>() не совпадают с JavaBeans-соглашением, а шаблон
     * ожидает getX()/isX(). Геттеры inputControlByName,
     * acceptedByName и cellReadyByName уже определены выше —
     * они отдают «—» вместо null.
     */
    public Long getSerialId() { return serialId; }
    public Integer getClassifierCode() { return classifierCode; }
    public String getClassifierName() { return classifierName; }
    public String getSensorClassName() { return sensorClassName; }
    public ProductType getProductType() { return productType; }
    public Integer getSerial() { return serial; }
    public ProductSerialStatus getStatus() { return status; }
    public String getZnpNumber() { return znpNumber; }
    public String getIncomingOrderNumber() { return incomingOrderNumber; }
    public String getChangeNumber() { return changeNumber; }
    public String getSupplierName() { return supplierName; }
    public String getProductionOrderNumber() { return productionOrderNumber; }
    public String getProductionOrderItem() { return productionOrderItem; }
    public String getUnitName() { return unitName; }
    public String getInputControlLabel() { return inputControlLabel; }
    public LocalDateTime getInputControlAt() { return inputControlAt; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public LocalDateTime getCellReadyAt() { return cellReadyAt; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public List<String> getCompletedOperationNames() { return completedOperationNames; }
}
