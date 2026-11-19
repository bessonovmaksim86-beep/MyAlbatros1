package ru.company.production.dto;

/**
 * Единица показа в сводной строке операции — прибор или ячейка.
 *
 * Мастер закрепляет работу над конкретным изделием, но когда всю
 * операцию ведёт один человек, он не хочет нажимать «Назначить» в
 * каждой строке: чекбокс «выделить все» собирает отмеченные единицы в
 * одно назначение. Строка несёт {@code rowId} — ключ назначения, —
 * потому что назначаются строки плана, а не изделия как таковые.
 *
 * @param rowId         строка плана — ключ назначения и снятия
 * @param instanceIndex порядковый номер изделия в заказе, 1-based —
 *                      подпись чекбокса, потому что номера склад
 *                      вносит позже и не обязан внести все
 * @param serialId      идентификатор прибора/ячейки в реестре; пустой,
 *                      пока номер не присвоен
 * @param serialNumber  полный номер прибора/ячейки («121200022»)
 * @param serialName    наименование изделия по классификатору
 * @param assigned      за работой уже закреплён исполнитель
 * @param executorName  его фамилия для подписи колонки
 * @param completed     работа проведена
 * @param isDevice      true для GROUP_DEVICE — здесь прибор, а не ячейка
 */
public record GroupZnpAssignRow(
        Long rowId,
        int instanceIndex,
        Long serialId,
        String serialNumber,
        String serialName,
        boolean assigned,
        String executorName,
        boolean completed,
        boolean isDevice
) {

    /**
     * Подпись чекбокса: «Прибор 3» либо «Ячейка 3».
     *
     * Номер изделия порядковый, а не серийный: мастер распределяет
     * работу до того, как номер присвоен, и подпись обязана оставаться
     * понятной с обеих сторон этого момента.
     */
    public String cellLabel() {
        return (isDevice ? "Прибор " : "Ячейка ") + instanceIndex;
    }

    /** Серийный номер прибора/ячейки; прочерк, если номер не присвоен. */
    public String serialLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? "—"
                : serialNumber.trim();
    }

    /** Номер присвоен (прибор выпущен или ячейка внесена в заказ). */
    public boolean attached() {
        return serialId != null;
    }

    /** Наименование изделия; пустое значение — прочерк. */
    public String nameLabel() {
        return serialName == null || serialName.isBlank()
                ? "—"
                : serialName.trim();
    }

    /*
     * Двойник вида isX() держится рядом с аксессором record намеренно:
     * Thymeleaf читает свойства по JavaBeans-соглашению, а record
     * компилируется с аксессором <имя>() без префикса.
     */
    public boolean isAttached() {
        return attached();
    }
}
