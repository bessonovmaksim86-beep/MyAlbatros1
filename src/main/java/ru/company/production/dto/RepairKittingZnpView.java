package ru.company.production.dto;

import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.ZnpKind;

/**
 * Ремонтный заказ-комплектующий в меню «Моих операций».
 *
 * Строка раскрывается в форму комплектования, поэтому держит всё, что
 * исполнитель сверяет до перехода: номер заказа, план, изделие,
 * количество, маршрут и готовность. Сами сущности в представление не
 * кладутся: {@code open-in-view} выключен, и шаблон, обратившийся к
 * lazy-связи заказа, упёрся бы в ленивую загрузку вне сессии.
 *
 * Готовность считается в сервисе, а не в шаблоне: «Готово X из Y» —
 * отношение уже взятых ячеек к количеству заказа, и выводить его из
 * двух разных списков в Thymeleaf означало бы считать состав заказа на
 * каждой перерисовке меню.
 */
public record RepairKittingZnpView(
        Long orderId,
        String number,
        String plannedDateLabel,
        String classifierLabel,
        String quantityLabel,
        String techProcessLabel,
        ZnpKind znpKind,
        long doneCount,
        long requiredCount
) {

    /** Подпись готовности — та же формулировка, что в сворачиваемом меню. */
    public String readinessLabel() {
        return "Готово " + doneCount + " из " + requiredCount;
    }

    /**
     * Заказ укомплектован: брать больше ячеек нельзя, и кнопка гаснет.
     *
     * Имя без префикса is — как у прочих представлений списка
     * (OperationInstance#completed): шаблон обращается к ним одинаково,
     * и исключение в одном месте читалось бы опечаткой.
     */
    public boolean completed() {
        return requiredCount > 0 && doneCount >= requiredCount;
    }

    /** Сколько ячеек осталось взять — подсказка у кнопки «Открыть». */
    public long remainingCount() {
        return Math.max(requiredCount - doneCount, 0L);
    }

    /**
     * Разновидность ячеек, которую допускает этот заказ.
     *
     * Перечень изолятора отфильтрован по ней заранее: в заказ на ремонт
     * ячеек нельзя взять подсборку — у них разные изделия и разные
     * маршруты, и смешанный состав заказ не закрыл бы.
     */
    public QuarantineCellKind requiredKind() {
        return znpKind == null
                ? QuarantineCellKind.CELL
                : znpKind.requiredCellKind();
    }

    public String requiredKindLabel() {
        return requiredKind().getDisplayName();
    }

    /**
     * Класс полосы готовности.
     *
     * Состояние считается здесь, а не разбирается вложенным условием в
     * {@code th:classappend}: Thymeleaf не смешивает в одном выражении
     * {@code ${...}} и «голые» вызовы, и шаблон с таким вложением падает
     * на разборе всего списка «Моих операций».
     */
    public String readinessStateClass() {
        if (completed()) {
            return " readiness--full";
        }

        return doneCount > 0 ? " readiness--partial" : " readiness--empty";
    }

    /** Доля готовности для полосы прогресса: 0..100. */
    public int readinessPercent() {
        if (requiredCount <= 0) {
            return 0;
        }

        long percent = Math.round(doneCount * 100.0 / requiredCount);

        return (int) Math.min(percent, 100L);
    }
}
