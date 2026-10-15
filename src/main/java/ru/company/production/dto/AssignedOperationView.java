package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Группа операций, уже закреплённых за исполнителем.
 *
 * Второй взгляд на ту же пару «операция + дата плана», что и
 * {@link DistributionOperation}, но с другой стороны: там мастер
 * выбирает, кому отдать работу, здесь он видит, кому отдал, и может
 * передумать. Отдельный DTO нужен потому, что набор подписей разный:
 * распределённой группе нужны часы кандидатов, закреплённой — фамилия
 * исполнителя и подпись того, кто закрепил.
 *
 * Тот же DTO обслуживает меню «Мои операции»: там к закреплённым
 * работам добавляются операции получения комплектации, и различает их
 * поле {@code source}.
 *
 * @param operationId   операция справочника — ключ группы
 * @param operationCode шифр операции
 * @param operationName наименование операции
 * @param plannedDate   дата плана этой группы
 * @param rowCount      сколько экземпляров операции в группе
 * @param totalHours    суммарная трудоёмкость группы, человеко-часы
 * @param orderNumbers  заказы покупателя, которым принадлежат строки
 * @param executorName  исполнитель группы, если он закреплен
 * @param source        природа группы: «Распределено» или «Получение
 *                      комплектации» — в «Мои операции» две природы
 *                      смешиваются в один список
 * @param ownWork       группа закреплена планом, а не решением мастера:
 *                      получение комплектации либо операция с
 *                      руководящей ролью исполнителя. Такую работу нельзя
 *                      снять и нельзя переложить на подчинённого
 * @param instances     экземпляры операции: номер изделия, часы и
 *                      признак, можно ли провести этот экземпляр
 * @param serialNumbers серийные номера группы без повторов — подпись
 *                      строки таблицы до раскрытия панели
 * @param itemNames     наименования изделий группы без повторов
 */
public record AssignedOperationView(
        Long operationId,
        String operationCode,
        String operationName,
        LocalDate plannedDate,
        long rowCount,
        BigDecimal totalHours,
        List<String> orderNumbers,
        String executorName,
        String source,
        boolean ownWork,
        List<OperationInstance> instances,
        List<String> serialNumbers,
        List<String> itemNames
) {

    /**
     * Серийные номера группы одной строкой — та же подпись, что в
     * очереди распределения, чтобы оба списка читались одинаково.
     */
    public String serialsLabel() {
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            return null;
        }

        String joined = String.join(", ", serialNumbers.stream()
                .limit(LABEL_LIMIT)
                .toList());

        return serialNumbers.size() > LABEL_LIMIT
                ? joined + " … всего " + serialNumbers.size()
                : joined;
    }

    /**
     * Наименование изделия главной строкой — та же подпись, что в
     * очереди распределения: полное обозначение занимает несколько
     * строк, поэтому показывается первое, а их число — счётчиком.
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

    /**
     * Сколько экземпляров ждёт предшественника — по этой цифре
     * строка таблицы помечается другим цветом.
     *
     * Считаются только те экземпляры, которым кнопка «Провести
     * операцию» положена по назначению: у получения комплектации её
     * нет ни у одного изделия, и помечать такую группу ожидающей
     * означало бы обещать действие, которого в ней не предусмотрено.
     */
    public long blockedCount() {
        if (instances == null) {
            return 0L;
        }

        if (ownWork) {
            return 0L;
        }

        return instances.stream()
                .filter(inst -> inst.blocked() && !inst.completed())
                .count();
    }

    /** Есть ли в группе заблокированные экземпляры. */
    public boolean hasBlocked() {
        return blockedCount() > 0;
    }

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

    /** Исполнитель или пояснение, что его не назначали. */
    public String executorLabel() {
        return executorName == null || executorName.isBlank()
                ? "не назначен"
                : executorName.trim();
    }

    /**
     * Изделия группы одной строкой: «поз. 3 · экз. 1, поз. 3 · экз. 2».
     *
     * Номер изделия исполнитель обязан видеть сразу, без раскрытия
     * строки: он определяет, какой именно экземпляр закрывать. Список
     * обрезается, иначе позиция на двадцать изделий растянула бы
     * строку таблицы — полный перечень остаётся в раскрывающемся
     * блоке.
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

    /** Сколько экземпляров уже проведено — прогресс по группе. */
    public long completedCount() {
        if (instances == null) {
            return 0L;
        }

        return instances.stream().filter(OperationInstance::completed).count();
    }
}