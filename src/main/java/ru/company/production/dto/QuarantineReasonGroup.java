package ru.company.production.dto;

import ru.company.production.entity.InputControlDefectReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Тип брака вместе с его видами: строка выпадающего списка фильтра
 * изолятора брака.
 *
 * Фильтр изолятора есть и у бюро входного контроля, и у службы качества,
 * а дерево причин одно на обоих: перечень причин отдают плоским списком
 * (дерево открытое по глубине, и целиком оно читается одним запросом),
 * поэтому сопоставлять вид его типу приходится в одном месте — иначе два
 * модуля разошлись бы в подписях одного и того же справочника.
 *
 * Группу строят здесь, а не в шаблоне: выражение вида {@code parent !=
 * null and parent.id == ...} в SpEL вычисляет обе части всегда, поэтому
 * для типа без ступени выше обращение к {@code parent.id} заканчивается
 * пустотой и рендеринг обрывается на середине страницы.
 *
 * {@code reason == null} — служебная группа уточнений, у которых тип из
 * справочника исчез (стал неактивным); шаблон рисует её плоским
 * перечнем, иначе такой вид брака просто пропал бы из фильтра.
 */
public record QuarantineReasonGroup(
        InputControlDefectReason reason,
        List<InputControlDefectReason> children
) {

    public InputControlDefectReason getReason() {
        return reason;
    }

    public List<InputControlDefectReason> getChildren() {
        return children;
    }

    /**
     * Плоский список причин → типы с видами (+ уточнения без типа).
     *
     * Тип держат первым элементом своей группы, ниже идут его уточнения:
     * выбранная причина верхнего уровня захватывает и уточнения, поэтому
     * фильтр по типу «Механическое повреждение» обязан найти и группы,
     * оформленные по виду «Скол корпуса».
     *
     * Уточнение, чей тип из справочника исчез, в группу не попадает —
     * такие отдают отдельной служебной группой в конце списка.
     */
    public static List<QuarantineReasonGroup> of(
            List<InputControlDefectReason> reasons) {

        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }

        Set<Long> known = new LinkedHashSet<>();

        for (InputControlDefectReason reason : reasons) {
            known.add(reason.getId());
        }

        Map<Long, List<InputControlDefectReason>> children =
                new LinkedHashMap<>();
        List<QuarantineReasonGroup> groups = new ArrayList<>();
        List<InputControlDefectReason> orphans = new ArrayList<>();

        for (InputControlDefectReason reason : reasons) {
            if (reason.getParent() != null) {
                continue;
            }

            groups.add(new QuarantineReasonGroup(reason, new ArrayList<>()));
        }

        for (InputControlDefectReason reason : reasons) {
            InputControlDefectReason parent = reason.getParent();

            if (parent == null) {
                continue;
            }

            /*
             * Тип неактивен или удалён: вид всё равно должен
             * отобраться, поэтому его держат отдельной группой.
             */
            if (!known.contains(parent.getId())) {
                orphans.add(reason);
                continue;
            }

            children
                    .computeIfAbsent(parent.getId(), key -> new ArrayList<>())
                    .add(reason);
        }

        List<QuarantineReasonGroup> filled = new ArrayList<>();

        for (QuarantineReasonGroup group : groups) {
            filled.add(
                    new QuarantineReasonGroup(
                            group.reason(),
                            children.getOrDefault(
                                    group.reason().getId(),
                                    List.of()
                            )
                    )
            );
        }

        if (!orphans.isEmpty()) {
            filled.add(new QuarantineReasonGroup(null, orphans));
        }

        return filled;
    }
}
