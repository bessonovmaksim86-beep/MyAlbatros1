package ru.company.production.dto;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Неприсвоенная операция — операция, включённая в действующий
 * техпроцесс, но не закреплённая ни за одним сотрудником
 * подразделения.
 *
 * Строка одна на операцию справочника: та же операция входит в
 * несколько маршрутов, поэтому маршруты и изделия собираются в
 * перечни, а не дублируют строку отчёта.
 */
public class UnassignedOperationView {

    private final Long operationId;
    private final String operationName;
    private final String operationTypeName;
    private final String unitName;

    private final Set<String> processCodes = new LinkedHashSet<>();
    private final Set<String> productNames = new LinkedHashSet<>();

    public UnassignedOperationView(
            Long operationId,
            String operationName,
            String operationTypeName,
            String unitName
    ) {
        this.operationId = operationId;
        this.operationName = operationName;
        this.operationTypeName = operationTypeName;
        this.unitName = unitName;
    }

    /*
     * Маршрут и изделие дописываются построчно при группировке проб.
     * LinkedHashSet отсекает повторы и держит порядок маршрутов.
     */
    public void addRoute(String processCode, String productName) {
        if (processCode != null && !processCode.isBlank()) {
            processCodes.add(processCode);
        }

        if (productName != null && !productName.isBlank()) {
            productNames.add(productName);
        }
    }

    public Long getOperationId() {
        return operationId;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getOperationTypeName() {
        return operationTypeName;
    }

    public String getUnitName() {
        return unitName;
    }

    public String getProcessCodes() {
        return String.join(", ", processCodes);
    }

    public String getProductNames() {
        return String.join("; ", productNames);
    }
}
