package ru.company.production.dto;

import java.util.List;

/**
 * Ячейка группового ЗНП — раскрывающаяся строка второго уровня.
 *
 * Заказ заказывает группу ячеек одного наименования количеством (V37),
 * и планировщик заводит под это количество экземпляры маршрута (V56).
 * Единица показа здесь — именно заказанное изделие, а не
 * привязанный серийник: склад вносит номера позже и вправе внести не
 * все, поэтому строка обязана существовать до внесения и быть
 * распределённой, иначе первая же операция заказа осталась бы без
 * адресата.
 *
 * Раскрытие строки показывает операции маршрута именно этого изделия.
 *
 * @param instanceIndex порядковый номер изделия в заказе, 1-based —
 *                      подпись колонки, когда номера ещё не присвоены
 * @param serialId      идентификатор ячейки в реестре; пустой, пока
 *                      склад не внёс её в заказ
 * @param serialNumber  полный номер ячейки («121200022»)
 * @param serialName    наименование изделия по классификатору
 * @param operations    операции ячейки с исполнителями и подбором людей
 */
public record GroupZnpSerialRow(
        int instanceIndex,
        Long serialId,
        String serialNumber,
        String serialName,
        List<GroupZnpOperationRow> operations
) {

    /*
     * Подпись главной колонки.
     *
     * Мастер просил показывать в групповых ЗНП номера ячеек, а не
     * серийные номера изделий. Номер ячейки — это и есть порядковый
     * номер заказанного изделия: склад вносит ячейки по порядку
     * экземпляров, поэтому «Ячейка 3» остаётся тем же изделием и после
     * того, как ему присвоят серийник, и до того, как это случится.
     */
    public String cellLabel() {
        return "Ячейка " + instanceIndex;
    }

    /**
     * Номер ячейки из реестра; прочерк, если склад её ещё не внёс.
     *
     * Пустое значение здесь — норма, а не ошибка: работы экземпляра
     * существуют до внесения ячейки, и прочерк честнее выдуманного
     * номера.
     */
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

    /** Сколько операций цеха у ячейки в раскрытии. */
    public long operationCount() {
        return operations == null ? 0L : operations.size();
    }

    /** Сколько операций уже закреплено за людьми. */
    public long assignedCount() {
        if (operations == null) {
            return 0L;
        }

        return operations.stream()
                .filter(GroupZnpOperationRow::assigned)
                .count();
    }

    /** Подпись готовности: «2 / 5» — закреплено из общего числа работ. */
    public String progressLabel() {
        long total = operationCount();

        return total == 0 ? "нет работ" : assignedCount() + " / " + total;
    }

    /** Есть ли работа, которую ещё можно распределить. */
    public boolean hasAssignables() {
        return operationCount() > assignedCount();
    }

    /** У ячейки есть работы подразделения — иначе строка пуста. */
    public boolean hasOperations() {
        return operationCount() > 0;
    }

    /*
     * Подписи текущей работы.
     *
     * Строка ячейки подписывается одной операцией, потому что мастер
     * читает перечень ячеек как очередь: ему важно, чего ячейка ждёт
     * сейчас, а не весь её маршрут — маршрут лежит в раскрытии.
     * Текущей считается первая незакрытая работа; если закрыты все,
     * показывается последняя, чтобы строка не осталась пустой.
     */
    private GroupZnpOperationRow current() {
        if (operations == null || operations.isEmpty()) {
            return null;
        }

        return operations.stream()
                .filter(op -> !op.completed())
                .findFirst()
                .orElse(operations.get(operations.size() - 1));
    }

    /** Наименование текущей операции; пустая строка, если работ нет. */
    public String currentOperationLabel() {
        GroupZnpOperationRow current = current();

        return current == null ? "—" : current.title();
    }

    /** Трудоёмкость текущей операции: «1 ч.». */
    public String currentHoursLabel() {
        GroupZnpOperationRow current = current();

        return current == null ? "—" : current.hoursLabel() + " ч.";
    }

    /** Исполнитель текущей операции. */
    public String currentExecutorLabel() {
        GroupZnpOperationRow current = current();

        if (current == null) {
            return "—";
        }

        return current.assigned() && current.executorName() != null
                ? current.executorName().trim()
                : "не назначен";
    }

    /** Ячейка закрыта: все её работы проведены. */
    public boolean isClosed() {
        if (operations == null || operations.isEmpty()) {
            return false;
        }

        return operations.stream().allMatch(GroupZnpOperationRow::completed);
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
