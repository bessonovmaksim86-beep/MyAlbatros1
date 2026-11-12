package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ReworkReturn;

import java.util.List;
import java.util.Map;

/**
 * Карточка изделия в разделе «Возврат на доработку».
 *
 * Одна карточка — один экземпляр: БТК возвращает изделие целиком, а
 * начальник цеха решает по нему, какие свои операции переделывать.
 *
 * В карточку попадают только строки маршрута, принадлежащие
 * подразделениям цеха: строка контроля принадлежит бюро, и показывать
 * её здесь нечего — цех не может её провести.
 */
@Getter
@Setter
public class ReworkReturnCard {

    /*
     * Ключ экземпляра: «co:orderId:itemPos:idx» или
     * «po:poId:itemPos:idx» — тот же формат, что у ControlReturnService.
     */
    private String instanceKey;

    /* Номер основания — заказ покупателя или заказ на производство. */
    private String baseNumber;

    /* Номер экземпляра внутри позиции, 0-based. */
    private Integer instanceIndex;

    /*
     * Изделие экземпляра: наименование и номер.
     *
     * У выпускающегося ЗНП это серийный номер из реестра, у группового
     * — номер ячейки, внесённой складом. Цех различает по ним карточки
     * одного заказа, потому что номер основания у них совпадает.
     */
    private String itemName;

    private String itemNumber;

    /*
     * Строки маршрута этого экземпляра в подразделениях цеха — те,
     * что он может вернуть на доработку. Отсортированы по seq, то есть
     * по порядку маршрута: переделка читается сверху вниз.
     */
    private List<OrderScheduleRow> rows;

    /*
     * Открытые возвраты БТК по строкам экземпляра, сгруппированные по
     * строке. Это основание карточки: цеху показывают то, что вернули.
     */
    private Map<Long, List<ControlReturn>> returns;

    /*
     * Открытые возвраты, заведённые самим цехом, — подсветка строк,
     * которые уже сняты для переделки.
     */
    private Map<Long, List<ReworkReturn>> ownReturns;

    /** Есть ли открытые возвраты с контроля. */
    public boolean hasOpenReturns() {
        return returns != null && !returns.isEmpty();
    }

    /** Причина возврата с контроля — первая из отмеченных. */
    public String getReason() {

        if (returns == null) {
            return null;
        }

        for (List<ControlReturn> list : returns.values()) {
            if (!list.isEmpty()) {
                return list.get(0).getReason();
            }
        }

        return null;
    }

    /** Этап, с которого изделие вернули. */
    public String getControlOperation() {

        if (returns == null) {
            return null;
        }

        for (List<ControlReturn> list : returns.values()) {
            if (!list.isEmpty()) {
                return list.get(0).getControlOperation();
            }
        }

        return null;
    }

    /** Строки, отмеченные цехом для переделки. */
    public boolean isMarkedForRework(Long rowId) {
        return ownReturns != null && ownReturns.containsKey(rowId);
    }

    /** Строка, по которой БТК прислал открытый возврат. */
    public boolean isReturnedByControl(Long rowId) {
        return returns != null && returns.containsKey(rowId);
    }

    /**
     * Строка доступна для отметки.
     *
     * Отмечают не только проведённые строки: возврат БТК, заведённый
     * по старому порядку, уже снял проведение, и цех обязан иметь
     * возможность переотметить такую работу — иначе карточка
     * возвращается в список навсегда без единого активного чекбокса.
     * Снятие с незакрытой строки — пустое действие, дубль возврата
     * при этом не заводится (см. ReworkReturnService#markForRework).
     */
    public boolean isMarkable(OrderScheduleRow row) {
        if (row == null || row.getId() == null) {
            return false;
        }

        return row.getCompletedAt() != null
                || isMarkedForRework(row.getId())
                || isReturnedByControl(row.getId());
    }

    /**
     * Подпись изделия в заголовке карточки: «наименование — номер».
     *
     * Цех обязан видеть, о каком именно изделии речь, а не о номере
     * экземпляра внутри позиции: у группового ЗНП несколько карточек
     * одного заказа выглядят одинаково, и различаются они только
     * номером ячейки или серийным номером выпуска.
     */
    public String getItemLabel() {

        if (itemName == null || itemName.isBlank()) {
            return itemNumber;
        }

        if (itemNumber == null || itemNumber.isBlank()) {
            return itemName;
        }

        return itemName + " — " + itemNumber;
    }

    /**
     * Подпись основания возврата: «Вернулось с операции: X. Причина: Y».
     *
     * Операция и причина читаются вместе: без операции цех не понимает,
     * на каком этапе изделие завернули, а без причины — что переделывать.
     */
    public String getReturnLabel() {

        String stage = getControlOperation();
        String defect = getReason();

        if (stage == null && defect == null) {
            return null;
        }

        StringBuilder label = new StringBuilder();

        if (stage != null && !stage.isBlank()) {
            label.append("Вернулось с операции: ").append(stage.trim());
        }

        if (defect != null && !defect.isBlank()) {
            if (label.length() > 0) {
                label.append(". Причина: ");
            } else {
                label.append("Причина: ");
            }

            label.append(defect.trim());
        }

        return label.toString();
    }
}
