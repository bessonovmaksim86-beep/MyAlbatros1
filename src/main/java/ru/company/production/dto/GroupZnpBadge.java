package ru.company.production.dto;

/**
 * Цвет бейджа распределения группового ЗНП.
 *
 * Подпись состояния («распределить», «частично», «распределено»,
 * «нет работ») звучит одинаково у заказа и у операции, поэтому и
 * краситься она обязана одинаково. Классы лежат в одном месте, а не
 * переписываются в каждом DTO: расхождение двух списков мастер
 * прочитал бы как два разных смысла одного слова.
 */
final class GroupZnpBadge {

    private GroupZnpBadge() {
    }

    /** CSS-модификатор {@code .group-znp-status} для подписи состояния. */
    static String of(String label) {
        if (label == null) {
            return "group-znp-status--empty";
        }

        return switch (label) {
            case "распределить" -> "group-znp-status--todo";
            case "частично" -> "group-znp-status--part";
            case "распределено" -> "group-znp-status--done";
            default -> "group-znp-status--empty";
        };
    }

    /**
     * CSS-модификатор для статуса самого заказа.
     *
     * Enum заказа отдаёт модификаторы {@code draft}, {@code work},
     * {@code done}, {@code deleted}, которые раскрашивают класс
     * {@code .status} — он определён в таблицах диспетчера и
     * качества, но не цеха. Раздел живёт в цехе и отвечает своими
     * бейджами, поэтому чужой модификатор переводится в свой ряд:
     * иначе статус заказа вышел бы на экран неокрашенным текстом.
     */
    static String ofOrderStatus(String badgeClass) {
        if (badgeClass == null) {
            return "group-znp-status--empty";
        }

        return switch (badgeClass) {
            case "work" -> "group-znp-status--part";
            case "done" -> "group-znp-status--done";
            default -> "group-znp-status--empty";
        };
    }
}
