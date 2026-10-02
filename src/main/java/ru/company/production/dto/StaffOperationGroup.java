package ru.company.production.dto;

import ru.company.production.entity.Operation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Группа операций в выпадающем списке закрепления.
 *
 * В списке две природы операций, и смешивать их в один плоский перечень
 * нельзя: цеховые работы выбираются по роли исполнителя «Исполнитель»,
 * а операции получения комплектации привязаны к подразделению напрямую
 * и никакой роли исполнителя не требуют. Без подписи группы руководитель
 * не понял бы, почему одна из строк списка к его цеху отношения не
 * имеет, и закрепил бы не то.
 *
 * Группы строятся в сервисе, а не в шаблоне: пустая группа в
 * {@code <optgroup label="...">} рисует заголовок без единой строки, и
 * в цехе, где комплектацию не получают, в списке висел бы пустой
 * раздел.
 *
 * @param label     подпись группы
 * @param operations операции этой группы
 */
public record StaffOperationGroup(
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
     * Две выборки одного подразделения → непустые группы.
     *
     * Пустые группы отбрасываются, а операции, попавшие в обе выборки
     * (получение комплектации с ролью «Исполнитель»), остаются только в
     * первой: повторяющийся {@code value} в одном {@code select} — это
     * два одинаковых варианта, из которых пользователь не узнает, чем
     * они различаются.
     *
     * @param unitGenitive слово «цеха», «склада» или «бюро» — подпись
     *                     первой группы строится по нему, иначе
     *                     руководитель склада читал бы в списке
     *                     «Операции цеха»
     */
    public static List<StaffOperationGroup> of(
            List<Operation> workshopOperations,
            List<Operation> kitReceiptOperations,
            String unitGenitive
    ) {
        List<Operation> workshop =
                workshopOperations == null ? List.of() : workshopOperations;

        Set<Long> listed = new LinkedHashSet<>();

        for (Operation operation : workshop) {
            if (operation.getId() != null) {
                listed.add(operation.getId());
            }
        }

        List<Operation> kitReceipt = new ArrayList<>();

        if (kitReceiptOperations != null) {
            for (Operation operation : kitReceiptOperations) {
                if (operation.getId() != null
                        && listed.contains(operation.getId())) {
                    continue;
                }

                kitReceipt.add(operation);
            }
        }

        List<StaffOperationGroup> groups = new ArrayList<>();

        String unit = unitGenitive == null || unitGenitive.isBlank()
                ? "подразделения"
                : unitGenitive.trim();

        if (!workshop.isEmpty()) {
            groups.add(new StaffOperationGroup(
                    "Операции " + unit,
                    workshop
            ));
        }

        if (!kitReceipt.isEmpty()) {
            groups.add(new StaffOperationGroup(
                    "Получение комплектации",
                    kitReceipt
            ));
        }

        return groups;
    }
}
