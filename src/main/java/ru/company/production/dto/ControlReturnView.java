package ru.company.production.dto;

import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.ReworkReturn;
import ru.company.production.service.WorkshopStaffService;

import java.time.format.DateTimeFormatter;

/**
 * Открытый возврат — подсветка переделки.
 *
 * Экземпляр, возвращённый на доработку, обязан показывать причину и
 * кто её вернул прямо в панели «Мои операции»: исполнитель приходит
 * закрывать работу и должен понять, что именно переделывать, не
 * выходя в отдельный раздел.
 *
 * Возврат приходит из двух мест: {@link ControlReturn} — сигнал БТК,
 * пока цех не решил, что переделывать, и {@link ReworkReturn} —
 * собственное решение цеха, снявшего проведение. Сигнал БТК закрывается
 * в момент снятия (карточка уходит из раздела «Возврат на доработку»),
 * и без второго источника исполнитель остался бы со снятым проведением
 * без единого объяснения.
 *
 * @param rowId          строка графика, по которой возврат
 * @param reason         причина возврата
 * @param controlOperation операция контроля, с которой возвращён
 * @param returnedBy     подписанный сотрудник, вернувший экземпляр
 * @param returnedLabel  дата и время возврата для таблицы
 * @param fromRework     возврат заведён цехом, а не БТК — от этого
 *                       зависит подпись бейджа
 */
public record ControlReturnView(
        Long rowId,
        String reason,
        String controlOperation,
        String returnedBy,
        String returnedLabel,
        boolean fromRework
) {

    /** Подпись даты возврата — та же, что у даты проведения. */
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Создать из сущности: ФИО и дата подписываются здесь. */
    public static ControlReturnView from(ControlReturn ret) {
        return new ControlReturnView(
                ret.getScheduleRow() == null
                        ? null
                        : ret.getScheduleRow().getId(),
                ret.getReason(),
                ret.getControlOperation(),
                ret.getReturnedBy() == null
                        ? null
                        : WorkshopStaffService.displayName(ret.getReturnedBy()),
                ret.getReturnedAt() == null
                        ? null
                        : ret.getReturnedAt().format(RU_DATE_TIME),
                false
        );
    }

    /**
     * Создать из возврата цеха.
     *
     * Этап контроля у собственного возврата цеха отсутствует — цех
     * переделывает по своему решению, а не по этапу чужого контроля.
     */
    public static ControlReturnView from(ReworkReturn ret) {
        return new ControlReturnView(
                ret.getScheduleRow() == null
                        ? null
                        : ret.getScheduleRow().getId(),
                ret.getReason(),
                null,
                ret.getReturnedBy() == null
                        ? null
                        : WorkshopStaffService.displayName(ret.getReturnedBy()),
                ret.getReturnedAt() == null
                        ? null
                        : ret.getReturnedAt().format(RU_DATE_TIME),
                true
        );
    }

    /** Подпись бейджа: «Вернул: причина». */
    public String badgeLabel() {
        String who = returnedBy == null || returnedBy.isBlank()
                ? (fromRework ? "руководитель подразделения" : "БТК")
                : returnedBy.trim();

        return "Переделка: " + who;
    }

    /** Причина возврата одной строкой; прочерк вместо пустой подписи. */
    public String reasonLabel() {
        return reason == null || reason.isBlank()
                ? "—"
                : reason.trim();
    }
}
