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
 * @param cellNumber    номер ячейки, внесённой складом в групповой ЗНП
 *                      (V20); для строк выпуска — пусто
 * @param overdueDays   сколько дней просрочена: если дата плана прошла
 *                      и работа не проведена, сколько дней назад она
 *                      должна была быть выполнена; 0 — в норме
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
        String executorLabel,
        String cellNumber,
        long overdueDays
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

    /**
     * Номер ячейки — основная подпись датчика в групповой половине.
     *
     * У группового ЗНП серийного номера выпуска не бывает вовсе, и
     * изделие опознано номером, внесённым складом (V20). Прочерк
     * честнее плановой подписи «поз. · датчик»: работа существует до
     * того, как кладовщик внёс в заказ номер.
     */
    public String cellLabel() {
        return cellNumber == null || cellNumber.isBlank()
                ? "—"
                : cellNumber.trim();
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

    /**
     * Подпись просрочки: «просрочена на N дн.».
     *
     * Вычисляется по разнице между сегодня и оригинальной датой плана —
     * даже если строку перенесли, просрочка остаётся и показывает,
     * насколько работа опоздала.
     */
    public long overdueDays() {
        return overdueDays;
    }

    /** Есть ли просрочка: блок рисуется с предупреждением. */
    public boolean isOverdue() {
        return overdueDays > 0;
    }

    /**
     * Подпись дня просрочки со склонением: «просрочка 1 день»,
     * «просрочка 3 дня», «просрочка 5 дней».
     *
     * Пустая строка, когда работы в графике — шаблон не рисует бейдж.
     */
    public String overdueLabel() {
        if (overdueDays <= 0) {
            return "";
        }

        long mod10 = overdueDays % 10;
        long mod100 = overdueDays % 100;

        String noun;

        if (mod10 == 1 && mod100 != 11) {
            noun = "день";
        } else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            noun = "дня";
        } else {
            noun = "дней";
        }

        return "просрочка " + overdueDays + " " + noun;
    }
}
