package ru.company.production.dto;

import ru.company.production.entity.Operation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Группа операций в выпадающем списке конструктора маршрута.
 *
 * Группирует операции по типу (производственная, контрольная,
 * комплектовочная и т. д.), чтобы технолог сразу видел, к какой
 * категории относится каждая операция, а не искал её в
 * алфавитной россыпи.
 *
 * Группы строятся в сервисе через LinkedHashMap — порядок
 * заголовков соответствует первому encountering операции в
 * исходном списке, отсортированном по имени.
 *
 * @param label     подпись группы (название типа операции)
 * @param operations операции этой группы
 */
public record TechProcessOperationGroup(
        String label,
        List<Operation> operations
) {

    public String getLabel() {
        return label;
    }

    public List<Operation> getOperations() {
        return operations;
    }

    /**
     * Плоский список операций → сгруппированный по типу.
     *
     * @param operations операции, отсортированные по имени
     */
    public static List<TechProcessOperationGroup> of(
            List<Operation> operations
    ) {
        List<Operation> all =
                operations == null ? List.of() : operations;

        if (all.isEmpty()) {
            return List.of();
        }

        Map<String, List<Operation>> byType = new LinkedHashMap<>();

        for (Operation op : all) {
            String type = op.getOperationType() != null
                    ? op.getOperationType().getName()
                    : "Без типа";

            byType.computeIfAbsent(type, key -> new ArrayList<>())
                    .add(op);
        }

        List<TechProcessOperationGroup> groups = new ArrayList<>();

        for (Map.Entry<String, List<Operation>> entry : byType.entrySet()) {
            groups.add(new TechProcessOperationGroup(
                    entry.getKey(),
                    entry.getValue()
            ));
        }

        return groups;
    }
}
