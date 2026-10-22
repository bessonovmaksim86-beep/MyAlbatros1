package ru.company.production.dto;

/**
 * Одно предупреждение предпросмотра удаления.
 *
 * Текст формулирует сервис целиком, а не шаблон по частям: причина
 * отказа обязана называть конкретные номера и заказы («в состав ЗП
 * идут ячейки, принятые складом»), иначе администратор увидел бы
 * запрет без возможности его понять и снять другим действием.
 *
 * @param level  серьёзность — определяет и цвет, и доступность кнопки
 * @param title  короткая причина для строки предупреждения
 * @param detail что именно нашлось и что с этим делать; может быть null
 * @param blockedGroup группа, удалять которую нельзя; {@code null} —
 *         запрет относится ко всей операции, а не к одному её пункту
 */
public record DeletionWarning(
        DeletionWarningLevel level,
        String title,
        String detail,
        DeletionGroupKind blockedGroup
) {

    /** Предупреждение, не привязанное к конкретной группе. */
    public DeletionWarning(
            DeletionWarningLevel level,
            String title,
            String detail
    ) {
        this(level, title, detail, null);
    }

    /** Предупреждение, запрещающее удалять одну группу. */
    public static DeletionWarning ofGroup(
            DeletionGroupKind group,
            String title,
            String detail
    ) {
        return new DeletionWarning(
                DeletionWarningLevel.BLOCKING, title, detail, group
        );
    }

    public boolean isBlocking() {
        return level == DeletionWarningLevel.BLOCKING;
    }

    public boolean requiresConfirmation() {
        return level == DeletionWarningLevel.ATTENTION;
    }

    /** Запрет относится ко всей записи: выполнить операцию нельзя никак. */
    public boolean isGlobalBlock() {
        return isBlocking() && blockedGroup == null;
    }

    /** Нельзя удалять именно эту группу, остальное — можно. */
    public boolean blocks(DeletionGroupKind group) {
        return isBlocking() && blockedGroup == group;
    }
}
