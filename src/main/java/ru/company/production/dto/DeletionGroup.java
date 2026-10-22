package ru.company.production.dto;

import java.util.List;

/**
 * Одна группа связанного состава в предпросмотре удаления.
 *
 * @param kind        что именно считается
 * @param count       сколько строк найдено; нулевые группы на экран
 *                    не выносят — чекбокс с нулём выглядел бы ошибкой
 *                    подсчёта, а не пустой группой
 * @param samples     первые номера строк для подписи («121200022,
 *                    121200023 …») — администратор обязан видеть
 *                    хоть часть того, что уходит, а не только цифру
 */
public record DeletionGroup(
        DeletionGroupKind kind,
        long count,
        List<String> samples
) {

    /** Сколько номеров показывать в подписи группы, прежде чем свернуть. */
    private static final int SAMPLE_LIMIT = 4;

    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Подпись состава строкой предпросмотра: «121200022, 121200023, …
     * и ещё 21».
     *
     * Пустой перечень отдаётся пустой строкой, а не прочерком: группу
     * с нулём на экран не выносят, а единичная строка без номеров
     * (например, позиции выпуска) не должна выглядеть потерянной.
     */
    public String getSampleLabel() {
        if (samples.isEmpty()) {
            return "";
        }

        List<String> shown = samples.size() <= SAMPLE_LIMIT
                ? samples
                : samples.subList(0, SAMPLE_LIMIT);

        String tail = samples.size() < count
                ? ", … и ещё " + (count - shown.size())
                : "";

        return String.join(", ", shown) + tail;
    }
}
