package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Одно изделие (один датчик) внутри строки-операции распределения.
 *
 * Строка таблицы называет операцию, а решение о человеке мастер
 * принимает над конкретным датчиком: раскрытие операции даёт по блоку на
 * каждый экземпляр, и у каждого блока свой выбор исполнителя и своя
 * кнопка. Поэтому блок подписан серийным номером одного датчика, а не
 * списком серийников изделия.
 *
 * @param rowId         строка графика — ключ назначения и снятия
 * @param itemPosition  позиция заказа, для заказа на производство — null
 * @param instanceIndex номер экземпляра внутри позиции, 0-based
 * @param plannedDate   дата плана этой операции
 * @param serialNumber  серийный номер датчика, до выпуска — пусто
 * @param itemFullName  полное наименование изделия («как в КД»)
 * @param orderNumber   номер основания строки
 * @param hours         трудоёмкость экземпляра, человеко-часы
 * @param assigned      экземпляр закреплён за исполнителем
 * @param executorLabel закреплённый исполнитель (для распределённых)
 */
public record DistributionItemCard(
        Long rowId,
        Integer itemPosition,
        Integer instanceIndex,
        LocalDate plannedDate,
        String serialNumber,
        String itemFullName,
        String orderNumber,
        BigDecimal hours,
        boolean assigned,
        String executorLabel
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Подпись датчика: серийный номер, а до выпуска — плановая подпись.
     *
     * Заказ планируется до выпуска (V43), поэтому до выпуска датчик
     * опознаётся позицией и номером внутри неё — прочерк оставил бы
     * мастера без ответа, над каким именно датчиком он ставит
     * исполнителя.
     */
    public String serialLabel() {
        return serialNumber == null || serialNumber.isBlank()
                ? planLabel()
                : serialNumber.trim();
    }

    /** Плановая подпись датчика: «поз. 3 · датчик 2». */
    public String planLabel() {
        int position = itemPosition == null ? 0 : itemPosition;
        int index = instanceIndex == null ? 0 : instanceIndex;

        return "поз. " + position + " · датчик " + (index + 1);
    }

    /** Дата плана внутри блока датчика. */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Наименование изделия одной строкой. */
    public String itemNameLabel() {
        return itemFullName == null || itemFullName.isBlank()
                ? "—"
                : itemFullName.trim();
    }

    /** Трудоёмкость датчика без хвостовых нулей. */
    public String hoursLabel() {
        if (hours == null) {
            return "—";
        }

        return hours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
