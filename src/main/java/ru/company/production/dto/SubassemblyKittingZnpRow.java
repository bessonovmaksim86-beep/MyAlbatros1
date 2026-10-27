package ru.company.production.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка списка комплектования подсборок: ЗНП, подсборки которого
 * принимает склад полуфабрикатов.
 *
 * Операцию приёмки раздел ищет у заказа (тип RECEIVING) либо в его
 * техпроцессе (тип KITTING_SUBASSEMBLY) — в обоих случаях она обязана
 * относиться к подразделению «Склад полуфабрикатов». Кладовщик открывает
 * заказ и вводит номера ячеек, которые есть на складе.
 *
 * Техпроцесс показан вместо родителя: групповой ЗНП заводят без выпуска
 * (V38), и колонка «Родитель» была бы в его строке прочерком, тогда как
 * маршрут заказа кладовщику полезен — по нему сверяют операцию приёмки.
 *
 * Изделие заказа печатается кодом и наименованием классификатора с
 * номером изменения ({@code classifierLabel}): номер берут из последней
 * партии прихода того же изделия, чтобы кладовщик не перепутал изменения
 * одного изделия. Отдельный тип изделия в списке был прочерком у
 * заказанных не по складскому термину позиций и не нёс информации,
 * которой нет в подписи изделия.
 *
 * Срок ({@code formattedDueDate}) — дата окончания плана заказа, а не
 * срок из его карточки: склад ориентируется на график, который уже
 * занимает цех.
 */
public record SubassemblyKittingZnpRow(
        Long id,
        String number,
        String znpKindDisplayName,
        String classifierLabel,
        String kittingTypeDisplayName,
        String techProcessCode,
        String operationName,
        String formattedOrderDate,
        String formattedDueDate,
        String statusDisplayName,
        String statusBadgeClass,
        String note,
        BigDecimal requiredQuantity,
        List<SubassemblyKittingCellRow> cells,
        List<SubassemblyKittingCellRow> attached
) {

    /**
     * Заказ «изготовление ячеек из подсборок» (V72): склад принимает в
     * нём ещё не принятые подсборки и переводит их в ячейки.
     *
     * Признак нужен шаблону не для красоты: у такого заказа перечень
     * «свободного» склада — это подсборки, и подписи на экране должны
     * говорить «подсборки», а не «принятые ячейки».
     */
    public boolean subassemblyKitting() {
        return kittingTypeDisplayName != null
                && !kittingTypeDisplayName.isBlank();
    }

    /*
     * Сколько ячеек уже оформлено в заказ.
     *
     * Считается по внесённому составу, а не по складскому перечню:
     * проведённая в ЗНП ячейка со склада снята и в свободном остатке
     * не числится, поэтому «взято» меряется матрицей заказа.
     */
    public long doneCount() {
        return attached == null ? 0L : attached.size();
    }

    /*
     * Сколько ячеек заказано.
     *
     * Готовность меряют заказом, а не складским остатком: на складе
     * лежит тридцать подсборок, а в заказ их входит пять, и «взято 2 из
     * 30» кладовщик прочёл бы как недоукомплектованный заказ.
     *
     * Количество задают только групповым и ремонтным видам. У заказа на
     * комплектования количества нет — там знаменателем остаётся всё,
     * что по этому изделию побывало на складе: свободный остаток плюс
     * уже оформленное в заказ (проведённую ячейку склад уже не показывает).
     */
    public long requiredCount() {
        return requiredQuantity == null
                ? doneCount() + availableCount()
                : requiredQuantity.setScale(0, java.math.RoundingMode.CEILING)
                .longValue();
    }

    /** Сколько свободных ячеек лежит на складе. */
    public long availableCount() {
        return cells == null ? 0L : cells.size();
    }

    /** Подпись готовности: «Взято X из Y». */
    public String readinessLabel() {
        return "Взято " + doneCount() + " из " + requiredCount();
    }

    /** Заказ укомплектован: взято заказанное количество. */
    public boolean completed() {
        long required = requiredCount();
        return required > 0 && doneCount() >= required;
    }

    /**
     * Заказ укомплектован не полностью: взято меньше заказанного.
     *
     * Обратная сторона {@link #completed()}, а не отдельный расчёт: по
     * этому признаку считают счётчик меню, и цифра обязана равняться
     * числу строк «Взято X из Y», где X меньше Y. Случай «заказано
     * ноль» в счётчик не входит: заказывать нечего и брать нечего, и
     * звать кладовщика в такую строку нечем.
     */
    public boolean incomplete() {
        long required = requiredCount();
        return required > 0 && doneCount() < required;
    }
}