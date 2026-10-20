package ru.company.production.dto;

import ru.company.production.entity.Operation;

import java.util.List;

/**
 * Группа операций в выпадающем списке закрепления.
 *
 * Группа одна и в неё входят все операции подразделения — и цеховые
 * работы с ролью исполнителя «Исполнитель», и операции, привязанные
 * к подразделению напрямую (приёмка, передача, получение
 * комплектации). Подпись группы строится по роду подразделения,
 * иначе руководитель склада читал бы в списке «Операции цеха».
 *
 * Группы строятся в сервисе, а не в шаблоне: пустая группа в
 * {@code <optgroup label="...">} рисует заголовок без единой строки,
 * и в подразделении без своих операций в списке висел бы пустой
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
     * Все операции подразделения → одна группа.
     *
     * @param unitGenitive слово «цеха», «склада» или «бюро» — подпись
     *                     группы строится по нему, иначе
     *                     руководитель склада читал бы в списке
     *                     «Операции цеха»
     */
    public static List<StaffOperationGroup> of(
            List<Operation> operations,
            String unitGenitive
    ) {
        List<Operation> all =
                operations == null ? List.of() : operations;

        if (all.isEmpty()) {
            return List.of();
        }

        String unit = unitGenitive == null || unitGenitive.isBlank()
                ? "подразделения"
                : unitGenitive.trim();

        return List.of(new StaffOperationGroup(
                "Операции " + unit,
                all
        ));
    }
}
