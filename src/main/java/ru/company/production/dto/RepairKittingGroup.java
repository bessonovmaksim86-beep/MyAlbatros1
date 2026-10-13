package ru.company.production.dto;

import ru.company.production.entity.QuarantineCellKind;

import java.util.List;

/**
 * Группа меню «Моих операций»: операция БВК и её ремонтные ЗНП.
 *
 * Меню строят по имени операции, закреплённой за исполнителем, потому
 * что исполнитель мыслит операцией («моей комплектовочной»), а заказом
 * — только внутри неё: заказов у одной операции несколько, и плоско
 * перечислить их означало бы заставить человека искать, к какой работе
 * относится каждый.
 *
 * Разновидность ячейки входит в ключ группы: заказ на ремонт ячеек и
 * заказ на ремонт подсборок ведутся одной и той же операцией (маршруты
 * у изделий разные, а операция бюро одна), но форма комплектования у них
 * разная — перечень изолятора отфильтрован по разновидности. Без этого
 * признака в одной группе оказались бы заказы с разными условиями
 * отбора, и подпись группы обещала бы не то, что откроется по клику.
 *
 * Счётчик группы — число заказов, а не ячеек: раскрывать группу
 * побуждает объём работ по заказам, а готовность видна в каждой строке
 * раскрытия.
 */
public record RepairKittingGroup(
        QuarantineCellKind cellKind,
        Long operationId,
        String operationName,
        List<RepairKittingZnpView> orders
) {

    public int orderCount() {
        return orders == null ? 0 : orders.size();
    }

    /** Подпись разновидности — заголовок блока меню. */
    public String cellKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }

    /** Все заказы группы закрыты — группу можно показать «зелёной». */
    public boolean completed() {
        return orders != null
                && !orders.isEmpty()
                && orders.stream().allMatch(RepairKittingZnpView::completed);
    }

    /** Сколько ячеек по всем заказам группы ещё не взято. */
    public long remainingCount() {
        if (orders == null) {
            return 0L;
        }

        return orders.stream().mapToLong(RepairKittingZnpView::remainingCount).sum();
    }
}
