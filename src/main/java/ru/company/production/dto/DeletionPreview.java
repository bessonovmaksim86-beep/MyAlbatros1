package ru.company.production.dto;

import ru.company.production.entity.DeletionAction;
import ru.company.production.entity.DeletionTargetKind;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Предпросмотр удаления: что именно будет удалено и чем это грозит.
 *
 * Раздел устроен в два шага намеренно. Жёсткое удаление заказа
 * затрагивает десятки строк в семи таблицах, часть из которых
 * принадлежит другим ролям — цеху, складу, бюро входного контроля, —
 * и односоставная кнопка «удалить» означала бы, что администратор
 * узнаёт о последствиях уже после того, как их не отменить. Поэтому
 * первый шаг только читает и показывает, а второй выполняет ровно то,
 * что показано.
 *
 * @param kind             тип удаляемой записи
 * @param id               идентификатор записи
 * @param label            номер записи для подтверждения («ЗП-2026-014»,
 *                         «ордер П-114, поставщик «Альфа»»)
 * @param subtitle         реквизиты, по которым запись опознают: заказ —
 *                         уровень и статус, партия — поставщика и диапазон
 * @param groups           непустые группы связанного состава
 * @param warnings         предупреждения, отсортированные от серьёзных
 * @param action           чем закончится выполнение
 * @param confirmationToken подпись показанного состава
 */
public record DeletionPreview(
        DeletionTargetKind kind,
        Long id,
        String label,
        String subtitle,
        List<DeletionGroup> groups,
        List<DeletionWarning> warnings,
        DeletionAction action,
        String confirmationToken
) {

    /** Есть ли что удалять, кроме самой записи. */
    public boolean hasLinkedRecords() {
        return groups.stream().anyMatch(group -> !group.isEmpty());
    }

    /** Суммарное число удаляемых строк — цифра в заголовке предпросмотра. */
    public long totalRecords() {
        return groups.stream()
                .mapToLong(DeletionGroup::count)
                .sum();
    }

    /**
     * Удаление записи целиком запрещено: кнопка выполнения на экране не
     * появляется.
     *
     * Учитываются только запреты без привязки к группе: запрет отдельной
     * группы не отменяет операцию, а вычёркивает один её пункт — так
     * откат прихода остаётся доступным, когда удалять реестр номеров
     * нельзя, а отвязать партию можно.
     */
    public boolean isBlocked() {
        return warnings.stream().anyMatch(DeletionWarning::isGlobalBlock);
    }

    /** Группы, которые отмечать нельзя, — чекбокс показан недоступным. */
    public Set<DeletionGroupKind> blockedGroups() {
        return warnings.stream()
                .map(DeletionWarning::blockedGroup)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(
                        () -> EnumSet.noneOf(DeletionGroupKind.class)
                ));
    }

    /** Запрет для группы: его текст идёт подписью к недоступному чекбоксу. */
    public DeletionWarning blockerFor(DeletionGroupKind group) {
        return warnings.stream()
                .filter(warning -> warning.blocks(group))
                .findFirst()
                .orElse(null);
    }

    /**
     * Нужно ли отдельное подтверждение отметкой.
     *
     * Требование ставит любое предупреждение уровня ATTENTION: оно
     * означает, что удаляются следы чужой работы (факт операций цеха,
     * отметки приёмки склада, напечатанные этикетки), и молча
     * проскользнуть такое не должно.
     */
    public boolean requiresConfirmation() {
        return warnings.stream().anyMatch(DeletionWarning::requiresConfirmation);
    }

    /** Предупреждения заданного уровня — для раздельных блоков экрана. */
    public List<DeletionWarning> warningsOf(DeletionWarningLevel level) {
        return warnings.stream()
                .filter(warning -> warning.level() == level)
                .toList();
    }

    /*
     * Выборки по уровням без аргумента: перечисление в выражении Thymeleaf
     * потребовало бы полного имени пакета в каждом вызове, а три блока
     * предупреждений на экране нужны всегда.
     */

    public List<DeletionWarning> getBlockingWarnings() {
        return warningsOf(DeletionWarningLevel.BLOCKING);
    }

    public List<DeletionWarning> getAttentionWarnings() {
        return warningsOf(DeletionWarningLevel.ATTENTION);
    }

    public List<DeletionWarning> getInfoWarnings() {
        return warningsOf(DeletionWarningLevel.INFO);
    }

    /** Группы, которые экран обязан показать отмеченными и недоступными. */
    public List<DeletionGroup> mandatoryGroups() {
        return groups.stream()
                .filter(group -> !group.isEmpty())
                .filter(group -> group.kind().isMandatory())
                .toList();
    }

    /** Итоговая строка журнала: «группа: число» через точку с запятой. */
    public String affectedCountsLabel() {
        return groups.stream()
                .filter(group -> !group.isEmpty())
                .map(group -> group.kind().getTitle() + ": " + group.count())
                .reduce((left, right) -> left + "; " + right)
                .orElse("связанных записей нет");
    }
}
