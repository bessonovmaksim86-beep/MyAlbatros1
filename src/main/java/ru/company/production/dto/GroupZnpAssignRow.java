package ru.company.production.dto;

/**
 * Ячейка в сводной строке операции — единица пакетного назначения.
 *
 * Мастер закрепляет работу над конкретной ячейкой, но когда всю
 * операцию ведёт один человек, он не хочет нажимать «Назначить» в
 * каждой строке: чекбокс «выделить все» собирает отмеченные ячейки
 * в одно назначение. Строка несёт {@code rowId} — ключ назначения, —
 * потому что назначаются строки плана, а не ячейки как таковые.
 *
 * @param rowId         строка плана — ключ назначения и снятия
 * @param instanceIndex порядковый номер изделия в заказе, 1-based —
 *                      подпись чекбокса, потому что номера ячеек склад
 *                      вносит позже и не обязан внести все
 * @param serialId      идентификатор ячейки в реестре; пустой, пока
 *                      склад не внёс её в заказ
 * @param serialNumber  полный номер ячейки («121200022»)
 * @param serialName    наименование изделия по классификатору
 * @param assigned      за работой уже закреплён исполнитель
 * @param executorName  его фамилия для подписи колонки
 * @param completed     работа проведена
 */
public record GroupZnpAssignRow(
        Long rowId,
        int instanceIndex,
        Long serialId,
        String serialNumber,
        String serialName,
        boolean assigned,
        String executorName,
        boolean completed
) {

    /**
     * Подпись чекбокса: «Ячейка 3».
     *
     * Номер изделия порядковый, а не серийный: мастер распределяет
     * работу до того, как склад присвоит ячейке номер, и подпись
     * обязана оставаться понятной с обеих сторон этого момента.
     */
    public String cellLabel() {
        return "Ячейка " + instanceIndex;
    }

    /** Серийный номер ячейки; прочерк, если склад её ещё не внёс. */
    public String serialLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? "—"
                : serialNumber.trim();
    }

    /** Ячейка внесена складом в заказ. */
    public boolean attached() {
        return serialId != null;
    }

    /** Наименование ячейки; пустое значение — прочерк. */
    public String nameLabel() {
        return serialName == null || serialName.isBlank()
                ? "—"
                : serialName.trim();
    }

    /*
     * Двойник вида isX() держит рядом с аксессором record намеренно:
     * Thymeleaf читает свойства по JavaBeans-соглашению, а record
     * компилируется с аксессором <имя>() без префикса.
     */
    public boolean isAttached() {
        return attached();
    }
}
