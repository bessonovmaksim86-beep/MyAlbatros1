package ru.company.production.dto;

import ru.company.production.entity.ControlReturn;
import ru.company.production.service.WorkshopStaffService;

import java.time.format.DateTimeFormatter;

/**
 * Открытый возврат с контроля БТК — подсветка переделки.
 *
 * Экземпляр, возвращённый на доработку, обязан показывать причину и
 * кто её вернул прямо в панели «Мои операции»: исполнитель приходит
 * закрывать работу и должен понять, что именно переделывать, не
 * выходя в отдельный раздел.
 *
 * @param rowId          строка графика, по которой возврат
 * @param reason         причина возврата
 * @param controlOperation операция контроля, с которой возвращён
 * @param returnedBy     подписанный контролёр, вернувший экземпляр
 * @param returnedLabel  дата и время возврата для таблицы
 */
public record ControlReturnView(
        Long rowId,
        String reason,
        String controlOperation,
        String returnedBy,
        String returnedLabel
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
                        : ret.getReturnedAt().format(RU_DATE_TIME)
        );
    }

    /** Подпись бейджа: «Вернул: причина». */
    public String badgeLabel() {
        String who = returnedBy == null || returnedBy.isBlank()
                ? "БТК"
                : returnedBy.trim();

        return "Переделка: " + who;
    }

    /** Причина одной строкой; прочерк вместо пустой подписи. */
    public String reasonLabel() {
        return reason == null || reason.isBlank()
                ? "—"
                : reason.trim();
    }
}
