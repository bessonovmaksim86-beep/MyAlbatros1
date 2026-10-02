package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Операция подразделения, ожидающая распределения по исполнителям.
 *
 * Единица распределения — пара «операция + дата плана», а не строка
 * графика: позиция на десять изделий даёт десять строк одной операции
 * в один день, и раскладывать их по людям мастер должен одним
 * решением, иначе он десять раз подряд выбирал бы того же сотрудника.
 *
 * {@code executors} заполняется сервисом сразу: отдельный запрос по
 * клику не нужен — сотрудников подразделения десятки, а загрузку
 * каждого мастер обязан видеть до выбора, а не после него.
 *
 * @param operationId   операция справочника — ключ группы
 * @param operationCode шифр операции
 * @param operationName наименование операции
 * @param plannedDate   дата плана этой группы
 * @param rowCount      сколько экземпляров операции в группе
 * @param totalHours    суммарная трудоёмкость группы, человеко-часы
 * @param orderNumbers  заказы покупателя, которым принадлежат строки
 * @param executors     сотрудники, способные выполнить операцию
 * @param instances     экземпляры операции: номер изделия и часы
 * @param serialNumbers серийные номера группы без повторов
 * @param itemNames     наименования изделий группы без повторов — берутся
 *                      из позиции заказа, поэтому видны и до выпуска,
 *                      когда серийного номера ещё нет
 */
public record DistributionOperation(
        Long operationId,
        String operationCode,
        String operationName,
        LocalDate plannedDate,
        long rowCount,
        BigDecimal totalHours,
        List<String> orderNumbers,
        List<AssignableExecutor> executors,
        List<OperationInstance> instances,
        List<String> serialNumbers,
        List<String> itemNames
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Сколько изделий перечисляется в подписи до обрезки. */
    private static final int LABEL_LIMIT = 4;

    /** Подпись группы для заголовка блока: «Сборка узла». */
    public String title() {
        return operationName == null || operationName.isBlank()
                ? "операция " + operationId
                : operationName.trim();
    }

    /** Дата плана в привычном формате — диалект java8time не подключён. */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Заказы группы одной строкой: «ЗП-12, ЗП-18». */
    public String ordersLabel() {
        return orderNumbers == null || orderNumbers.isEmpty()
                ? "—"
                : String.join(", ", orderNumbers);
    }

    /** Трудоёмкость группы без хвостовых нулей. */
    public String hoursLabel() {
        if (totalHours == null) {
            return "—";
        }

        return totalHours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Ни один сотрудник не способен: блок рисуется с предупреждением. */
    public boolean hasNoExecutor() {
        return executors == null || executors.isEmpty();
    }

    /**
     * Изделия группы одной строкой: «поз. 3 · экз. 1, поз. 3 · экз. 2».
     *
     * Номер изделия мастер обязан видеть до назначения: он закрепляет
     * работу не над абстрактной позицией, а над конкретными
     * экземплярами. Список обрезается, иначе позиция на двадцать
     * изделий растянула бы заголовок на весь экран — полный перечень
     * выводится отдельной таблицей под исполнителями.
     */
    public String instancesLabel() {
        if (instances == null || instances.isEmpty()) {
            return "—";
        }

        String joined = String.join(", ", instances.stream()
                .map(OperationInstance::productLabel)
                .limit(LABEL_LIMIT)
                .toList());

        return instances.size() > LABEL_LIMIT
                ? joined + " … всего " + instances.size()
                : joined;
    }

    /** Сколько экземпляров уже проведено — фон при назначении. */
    public long completedCount() {
        if (instances == null) {
            return 0L;
        }

        return instances.stream().filter(OperationInstance::completed).count();
    }

    /** Серийники группы одной строкой — видны без раскрытия панели. */
    public String serialsLabel() {
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            return null;
        }

        String joined = String.join(", ",
                serialNumbers.stream().limit(LABEL_LIMIT).toList());

        return serialNumbers.size() > LABEL_LIMIT
                ? joined + " … всего " + serialNumbers.size()
                : joined;
    }

    /*
     * Наименование изделия главной строкой.
     *
     * Полное обозначение датчика («как в КД») занимает две-три строки в
     * узкой колонке, поэтому перечислять все наименования группы смысла
     * нет: позиции одной операции обычно одно и то же изделие. Если их
     * несколько, показывается первое и счётчик остальных — полный
     * перечень остаётся в панели раскрытия.
     */
    public String itemNameLabel() {
        if (itemNames == null || itemNames.isEmpty()) {
            return null;
        }

        String first = itemNames.get(0).trim();

        return itemNames.size() > 1
                ? first + " … ещё " + (itemNames.size() - 1)
                : first;
    }

    /** Все наименования группы — выводятся списком в панели раскрытия. */
    public List<String> distinctItemNames() {
        return itemNames == null ? List.of() : itemNames;
    }
}