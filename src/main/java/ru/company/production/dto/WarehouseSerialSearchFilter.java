package ru.company.production.dto;

import ru.company.production.entity.InputControlType;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;

import java.time.LocalDate;
import java.util.List;

/**
 * Фильтр поиска серийного номера повреждённой этикетки.
 *
 * Используется кладовщиком и технологом, когда на изделии повреждена
 * QR-этикетка и нужно идентифицировать серийный номер по наводкам:
 * классификатор, операция маршрута, статус, период, заказ-комплектующий
 * и прочие признаки.
 *
 * Все поля необязательны — пользователь вводит то, что известно.
 * Пустое значение означает «не выбрано», а не ошибку (тот же приём,
 * что у {@link WarehouseStockFilter}).
 */
public record WarehouseSerialSearchFilter(

        Long classifierId,

        String productName,

        String numberHint,

        OperationFilter operationFound,

        List<OperationFilter> completedOperations,

        ProductSerialStatus status,

        ProductType productType,

        InputControlType inputControlType,

        LocalDate dateFrom,

        LocalDate dateTo,

        String supplierName,

        String incomingOrderNumber,

        String znpNumber,

        String productionOrderNumber,

        String unitName
) {

    /**
     * Операция как параметр отбора.
     *
     * Идентификатор берётся из operation_names, а не из строки
     * operations: одно наименование живёт в матрицах разных
     * подразделений, и искать нужно по нему.
     */
    public record OperationFilter(
            Long operationNameId,
            String operationName
    ) {

        public static OperationFilter ofId(Long id) {
            return new OperationFilter(id, null);
        }

        /*
         * Двойники вида getX() держат рядом с аксессорами record
         * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению,
         * а record компилируется с аксессором <имя>() без префикса.
         */
        public Long getOperationNameId() {
            return operationNameId;
        }

        public String getOperationName() {
            return operationName;
        }
    }

    /** Пустой фильтр — экран открывается без условий. */
    public static WarehouseSerialSearchFilter empty() {
        return new WarehouseSerialSearchFilter(
                null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null
        );
    }

    /** Задано ли хотя бы одно условие — по этому рисуют «сбросить». */
    public boolean isFiltered() {
        return classifierId != null
                || hasText(productName)
                || hasText(numberHint)
                || operationFound != null
                || (completedOperations != null && !completedOperations.isEmpty())
                || status != null
                || productType != null
                || inputControlType != null
                || dateFrom != null
                || dateTo != null
                || hasText(supplierName)
                || hasText(incomingOrderNumber)
                || hasText(znpNumber)
                || hasText(productionOrderNumber)
                || hasText(unitName);
    }

    /** Идентификатор операции «на которой обнаружена» для поля формы. */
    public Long operationFoundIdParam() {
        return operationFound == null ? null : operationFound.operationNameId();
    }

    /** Идентификаторы пройденных операций — для полей формы. */
    public List<Long> completedOperationIds() {
        if (completedOperations == null) {
            return List.of();
        }

        return completedOperations.stream()
                .map(OperationFilter::operationNameId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Значения полей формы — чтобы отфильтрованный экран возвращал свой выбор. */
    public String dateFromParam() {
        return dateFrom == null ? "" : dateFrom.toString();
    }

    public String dateToParam() {
        return dateTo == null ? "" : dateTo.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
