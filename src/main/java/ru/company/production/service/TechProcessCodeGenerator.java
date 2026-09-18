package ru.company.production.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Автогенерация кода техпроцесса в виде ТП-001, ТП-002 и т.д.
 * Номер выбирается как следующий свободный после уже занятых.
 */
@Component
public class TechProcessCodeGenerator {

    private static final String PREFIX = "ТП-";
    private static final Pattern NUMBERED =
            Pattern.compile("^ТП-(\\d+)$");

    /**
     * Возвращает ближайший свободный код вида ТП-NNN.
     * При неограниченном росте длины числовой части
     * ширина дополняется до трёх знаков минимум.
     */
    public String next(List<String> existingCodes) {
        int max = 0;

        if (existingCodes != null) {
            for (String code : existingCodes) {
                if (code == null) {
                    continue;
                }

                Matcher matcher =
                        NUMBERED.matcher(code.trim().toUpperCase());

                if (matcher.matches()) {
                    try {
                        max = Math.max(
                                max,
                                Integer.parseInt(matcher.group(1))
                        );
                    } catch (NumberFormatException ignored) {
                        /*
                         * Коды с переполнением игнорируются —
                         * они не участвуют в автогенерации.
                         */
                    }
                }
            }
        }

        int width = Math.max(3, String.valueOf(max + 1).length());

        return PREFIX + String.format(
                "%0" + width + "d",
                max + 1
        );
    }
}
