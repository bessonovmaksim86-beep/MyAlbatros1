package ru.company.production.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Автогенерация кода заказчика в виде ЗК-001, ЗК-002 и т.д.
 *
 * Код обязателен (колонка code NOT NULL и уникальна), но в форме
 * заказчика он не запрашивается — справочник заводится одним полем
 * наименования, поэтому код подбирается автоматически.
 */
@Component
public class CustomerCodeGenerator {

    private static final String PREFIX = "ЗК-";
    private static final Pattern NUMBERED =
            Pattern.compile("^ЗК-(\\d+)$");

    /**
     * Возвращает ближайший свободный код вида ЗК-NNN.
     * При росте числовой части ширина дополняется до трёх знаков минимум.
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
                         * Коды, заведённые вручную и не соответствующие
                         * шаблону, в автогенерации не участвуют.
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
