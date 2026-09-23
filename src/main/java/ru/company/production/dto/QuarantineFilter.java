package ru.company.production.dto;

import ru.company.production.entity.QuarantineCellKind;

/**
 * Фильтр перечня изолятора брака для руководителя службы качества.
 *
 * Разновидность ячейки, её классификатор и причина брака — три условия
 * отбора, заданные пользователем. Пустое значение означает «все»:
 * перечень открывают без фильтра, а условия добавляют по мере надобности.
 *
 * Причина задаётся узлом дерева: выбранный тип верхнего уровня захватывает
 * и его уточнения, потому что цепочка причин группы содержит оба шага.
 * Поэтому отдельного признака «тип / вид брака» в фильтре нет.
 */
public record QuarantineFilter(
        QuarantineCellKind kind,
        Long classifierId,
        Long reasonId
) {

    public static QuarantineFilter empty() {
        return new QuarantineFilter(null, null, null);
    }

    /** Задано ли хоть одно условие — по этому признаку рисуют «сбросить». */
    public boolean isFiltered() {
        return kind != null || classifierId != null || reasonId != null;
    }
}
