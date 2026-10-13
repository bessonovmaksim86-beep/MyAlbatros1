package ru.company.production.dto;

import ru.company.production.service.QuarantineCellView;

/**
 * Строка изолятора брака в форме комплектования ремонтного ЗНП.
 *
 * Это обёртка над {@link QuarantineCellView}, а не её замена: перечень
 * обязан выглядеть и отбираться ровно как на странице «Изолятор
 * брака», и только выбора здесь нет — отсюда два признака, которых в
 * изоляторе нет.
 *
 * Различаются два состояния «нельзя отметить»: ячейка уже взята именно
 * в этот заказ (отметка снята, и повторная проводка отклонена) и ячейка
 * взята в другой ремонтный заказ (её показывают, но предупреждают —
 * исполнитель иначе решил бы, что ячейку потеряли).
 */
public record KittingCellRow(
        QuarantineCellView cell,
        boolean takenByThisOrder,
        boolean takenSomewhere
) {

    /**
     * Отмечать нельзя: либо уже в этом заказе, либо занята другим.
     *
     * Снятие отметки — не украшение: чекбокс, который можно оставить
     * отмеченным, но который потом отклонит проверка, заставил бы
     * человека разбирать отказ вместо того, чтобы видеть ограничение
     * заранее.
     *
     * Имя без префикса is — по соглашению прочих представлений списка.
     */
    public boolean selectable() {
        return !takenSomewhere;
    }

    /** Подпись состояния в колонке «Состояние». */
    public String stateLabel() {

        if (takenByThisOrder) {
            return "взята в этот заказ";
        }

        return takenSomewhere ? "уже в ремонте" : "можно взять";
    }

    /** Модификатор CSS-метки состояния — соответствие цветам изолятора. */
    public String stateBadgeClass() {

        if (takenByThisOrder) {
            return "badge badge--success";
        }

        return takenSomewhere ? "badge badge--attention" : "badge";
    }

    /*
     * Делегаты подписям ячейки держатся здесь, а не в шаблоне:
     * Thymeleaf читает свойства record по JavaBeans-соглашению, и
     * обращение вида row.cell.fullNumber работает, но дублировать его
     * в каждой колонке таблицы — значит потерять единое место, откуда
     * берётся подпись.
     */
    public String getFullNumber() {
        return cell.getFullNumber();
    }

    public String getKindLabel() {
        return cell.getKindLabel();
    }

    public String getCellLabel() {
        return cell.getCellLabel();
    }

    public String getSupplierName() {
        return cell.getSupplierName();
    }

    public String getIncomingOrderNumber() {
        return cell.getIncomingOrderNumber();
    }

    public String getReasonChain() {
        return cell.getReasonChain();
    }

    public String getDecisionLabel() {
        return cell.getDecisionLabel();
    }

    public String getCompletedLabel() {
        return cell.getCompletedLabel();
    }

    public Long getClassifierId() {
        return cell.getClassifierId();
    }

    public Long getCardId() {
        return cell.getCardId();
    }

    public Long getGroupId() {
        return cell.getGroupId();
    }
}
