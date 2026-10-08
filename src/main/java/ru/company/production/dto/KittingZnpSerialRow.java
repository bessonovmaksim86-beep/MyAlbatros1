package ru.company.production.dto;

import java.util.List;

/**
 * Строка раздела «Комплектование ЗНП»: изделие и его операции.
 *
 * Прежний раздел перечислял операции плоским списком, где серийный
 * номер и наименование изделия повторялись в каждой строке группы.
 * Кладовщику удобнее обратный разрез: одна строка — одно изделие
 * (серийник + название датчика), а внутри раскрытия — операции
 * комплектования, под которые заведён активный ЗНП. Тот же приём, что
 * в «Мои операции» у исполнителей и начальников цехов.
 *
 * В список попадают только изделия, у которых есть хотя бы одна
 * операция с привязанным заказом-комплектующим в статусе «в работе»:
 * операция без такого ЗНП складу не видна, потому что проводить её
 * не под что.
 *
 * @param serialId     идентификатор изделия в реестре
 * @param serialNumber полный серийный номер («121200022»)
 * @param itemFullName наименование датчика («как в КД»)
 * @param operations   операции изделия с активным ЗНП
 */
public record KittingZnpSerialRow(
        Long serialId,
        String serialNumber,
        String itemFullName,
        List<KittingZnpOperationRow> operations
) {

    /** Серийный номер — подпись главной колонки; пустое значение — прочерк. */
    public String serialLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? "—"
                : serialNumber.trim();
    }

    /** Наименование датчика — вторая колонка; пустое значение — прочерк. */
    public String nameLabel() {
        return itemFullName == null || itemFullName.isBlank()
                ? "—"
                : itemFullName.trim();
    }

    /** Сколько операций у изделия в раскрытии. */
    public long operationCount() {
        return operations == null ? 0L : operations.size();
    }

    /**
     * Есть ли незакрытая операция, ждущая предшественника: по этой
     * цифре главная строка помечается янтарным, как в «Мои операции».
     */
    public boolean hasBlocked() {
        if (operations == null) {
            return false;
        }

        return operations.stream()
                .anyMatch(op -> op.blocked() && !op.completed());
    }

    /** Есть ли операция, которую можно провести прямо сейчас. */
    public boolean hasCompletable() {
        if (operations == null) {
            return false;
        }

        return operations.stream()
                .anyMatch(KittingZnpOperationRow::canComplete);
    }
}
