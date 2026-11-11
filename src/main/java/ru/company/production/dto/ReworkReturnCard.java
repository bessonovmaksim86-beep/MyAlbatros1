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
}
