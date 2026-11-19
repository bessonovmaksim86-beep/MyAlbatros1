package ru.company.production.dto;

import java.util.List;

/**
 * Единица показа группового ЗНП — раскрывающаяся строка второго уровня.
 *
 * Заказ заказывает группу изделий одного наименования количеством (V37),
 * и планировщик заводит под это количество экземпляры маршрута (V56).
 * Единица показа зависит от вида заказа: для GROUP_DEVICE — прибор
 * (выпущенный серийник изделия), для GROUP_CELL и ремонтных видов —
 * ячейка из матрицы комплектования. Строка обязана существовать до
 * присвоения номера и быть распределённой, иначе первая же операция
 * заказа осталась бы без адресата.
 *
 * Раскрытие строки показывает операции маршрута именно этого изделия.
 *
 * @param instanceIndex порядковый номер изделия в заказе, 1-based —
 *                      подпись колонки, когда номера ещё не присвоены
 * @param serialId      идентификатор прибора/ячейки в реестре; пустой,
 *                      пока номер не присвоен
 * @param serialNumber  полный номер прибора/ячейки («121200022»)
 * @param serialName    наименование изделия по классификатору
 * @param operations    операции с исполнителями и подбором людей
 * @param isDevice      true для GROUP_DEVICE — здесь прибор, а не ячейка
 */
public record GroupZnpSerialRow(
        int instanceIndex,
        Long serialId,
        String serialNumber,
        String serialName,
        List<GroupZnpOperationRow> operations,
        boolean isDevice
) {

    /*
     * Подпись главной колонки.
     *
     * Для GROUP_DEVICE подпись «Прибор N», для остальных видов —
     * «Ячейка N»: мастер просил показывать в групповых ЗНП на приборы
     * номера приборов, а не ячеек, и экземпляр плана — это именно
     * прибор.
     */
    public String cellLabel() {
        return (isDevice ? "Прибор " : "Ячейка ") + instanceIndex;
    }

    /**
     * Номер прибора/ячейки из реестра; прочерк, если номер ещё не
     * присвоен.
     *
     * Пустое значение здесь — норма, а не ошибка: работы экземпляра
     * существуют до присвоения номера, и прочерк честнее выдуманного
     * номера.
     */
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

    /** Сколько операций цеха у единицы показа в раскрытии. */
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

    /** У единицы показа есть работы подразделения — иначе строка пуста. */
    public boolean hasOperations() {
        return operationCount() > 0;
    }

    /*
     * Подписи текущей работы.
     *
     * Строка подписывается одной операцией, потому что мастер читает
     * перечень единиц как очередь: ему важно, чего изделие ждёт сейчас,
     * а не весь его маршрут — маршрут лежит в раскрытии. Текущей
     * считается первая незакрытая работа; если закрыты все, показывается
     * последняя, чтобы строка не осталась пустой.
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

    /** Единица показа закрыта: все её работы проведены. */
    public boolean isClosed() {
        if (operations == null || operations.isEmpty()) {
            return false;
        }

        return operations.stream().allMatch(GroupZnpOperationRow::completed);
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
