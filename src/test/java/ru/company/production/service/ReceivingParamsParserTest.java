package ru.company.production.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.company.production.service.ReceivingParamsParser.ReceivingParams;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор поисковой фразы на параметры прихода.
 *
 * Проверяется главное обещание разбора: фильтром становится то, что
 * из фразы прочитано, а непрочитанное условие не применяется. Отсюда
 * и проверки «только поставщик», «только период», «ничего не названо»
 * — оператор не обязан формулировать запрос полностью.
 */
class ReceivingParamsParserTest {

    private final ReceivingParamsParser parser = new ReceivingParamsParser();

    @Nested
    @DisplayName("Поставщик и период")
    class SupplierAndPeriod {

        @Test
        @DisplayName("Фраза целиком даёт и имя, и обе границы периода")
        void readsAllParameters() {
            ReceivingParams params = parser.parse(
                    "найди ордеры поставщика АО ГОЛ "
                            + "с 12.12.2025 до 14.12.2025");

            assertEquals("ао гол", params.supplierName());
            assertEquals(LocalDate.of(2025, 12, 12), params.dateFrom());
            assertEquals(LocalDate.of(2025, 12, 14), params.dateTo());
        }

        @Test
        @DisplayName("Имя состоит из слов и не схлопывается до последнего")
        void readsMultiWordName() {
            ReceivingParams params = parser.parse(
                    "поставщик Уралэнерго с 01.10.2025 по 10.10.2025");

            assertEquals("уралэнерго", params.supplierName());
        }

        @Test
        @DisplayName("Предлог внутри имени периодом не считается")
        void doesNotSplitNameOnInnerPreposition() {
            /*
             * «С» в начале «Спецмонтаж» — не предлог периода: срез по
             * подстроке разрубил бы имя и потерял бы поставщика.
             */
            ReceivingParams params = parser.parse(
                    "поставщик Спецмонтаж с 05.03.2026");

            assertEquals("спецмонтаж", params.supplierName());
            assertEquals(LocalDate.of(2026, 3, 5), params.dateFrom());
        }

        @Test
        @DisplayName("Правая граница включает последний день целиком")
        void upperBoundCoversWholeLastDay() {
            ReceivingParams params = parser.parse(
                    "поставщик гол с 12.12.2025 по 14.12.2025");

            /*
             * «по 14.12» обязано найти партию, оформленную 14-го в 23:59,
             * поэтому граница exclusive — начало 15-го.
             */
            assertEquals(
                    LocalDate.of(2025, 12, 15).atStartOfDay(),
                    params.toExclusive()
            );
        }
    }

    @Nested
    @DisplayName("Неполный запрос")
    class PartialRequest {

        @Test
        @DisplayName("Только поставщик — период не выдумывается")
        void supplierOnly() {
            ReceivingParams params = parser.parse("поставщик гол");

            assertEquals("гол", params.supplierName());
            assertNull(params.dateFrom());
            assertNull(params.dateTo());
        }

        @Test
        @DisplayName("Только период — поставщика нет")
        void periodOnly() {
            ReceivingParams params = parser.parse("с 12.12.2025");

            assertNull(params.supplierName());
            assertEquals(LocalDate.of(2025, 12, 12), params.dateFrom());
        }

        @Test
        @DisplayName("«с даты» — период открыт вправо")
        void openUpperBound() {
            ReceivingParams params = parser.parse("приходы с 12.12.2025");

            assertNull(params.dateTo());
            assertNull(params.toExclusive());
        }

        @Test
        @DisplayName("Дата без предлога — один день, а не «от даты и далее»")
        void singleDayIsClosed() {
            ReceivingParams params = parser.parse("приход 12.12.2025");

            assertEquals(LocalDate.of(2025, 12, 12), params.dateFrom());
            assertEquals(LocalDate.of(2025, 12, 12), params.dateTo());
        }

        @Test
        @DisplayName("Ничего не названо — фильтр не строится")
        void nothingNamed() {
            ReceivingParams params = parser.parse("датчик давления");

            assertFalse(params.hasAny());
            assertNull(params.supplierName());
            assertNull(params.dateFrom());
            assertNull(params.dateTo());
        }
    }

    @Nested
    @DisplayName("Написание даты")
    class DateFormats {

        @Test
        @DisplayName("Числом и словом читаются одинаково")
        void monthNameEqualsDigits() {
            assertEquals(
                    parser.parse("с 12.12.2025 по 14.12.2025").dateFrom(),
                    parser.parse("с 12 декабря 2025 по 14 декабря 2025")
                            .dateFrom()
            );
        }

        @Test
        @DisplayName("Смешанное написание сохраняет порядок периода")
        void mixedFormatsKeepOrder() {
            ReceivingParams params = parser.parse(
                    "с 12 декабря 2025 по 14.12.2025");

            assertEquals(LocalDate.of(2025, 12, 12), params.dateFrom());
            assertEquals(LocalDate.of(2025, 12, 14), params.dateTo());
        }

        @Test
        @DisplayName("Разделители — точка, слэш и дефис")
        void anySeparator() {
            assertEquals(
                    LocalDate.of(2025, 12, 1),
                    parser.parse("с 1/12/2025").dateFrom()
            );
            assertEquals(
                    LocalDate.of(2025, 12, 1),
                    parser.parse("с 1-12-2025").dateFrom()
            );
        }

        @Test
        @DisplayName("Месяц без года — год текущий")
        void yearDefaultsToCurrent() {
            assertEquals(
                    LocalDate.now().getYear(),
                    parser.parse("с 5 октября").dateFrom().getYear()
            );
        }

        @Test
        @DisplayName("Несуществующая дата датой не считается")
        void rejectsImpossibleDate() {
            assertFalse(parser.parse("13.14.2025").hasAny());
            assertFalse(parser.parse("31.02.2025").hasAny());
        }

        @Test
        @DisplayName("Номер заказа датой не считается")
        void orderNumberIsNotDate() {
            /*
             * «2026-005» — часть номера ЗП: две группы цифр, а не три.
             * Считать её датой значило бы отдать приходный фильтр вместо
             * поиска по номеру.
             */
            assertFalse(parser.parse("ЗП-2026-005").hasAny());
        }
    }

    @Nested
    @DisplayName("Остаток фразы для обычного поиска")
    class ResidualTerm {

        @Test
        @DisplayName("Даты и имя из остатка вырезаны")
        void parametersAreCut() {
            ReceivingParams params = parser.parse(
                    "найди ордеры поставщика АО ГОЛ "
                            + "с 12.12.2025 до 14.12.2025");

            /*
             * В остатке не должно быть ни «12.12.2025», ни «голл», ни
             * «поставщик»: LIKE по таким строкам ищет там, где этих
             * слов нет, и только мешает.
             */
            assertTrue(
                    params.term() == null
                            || !params.term().contains("2025"),
                    "остаток не должен содержать дату: " + params.term()
            );
            assertTrue(
                    params.term() == null
                            || !params.term().contains("поставщик"),
                    "остаток не должен содержать ключевое слово: "
                            + params.term()
            );
        }

        @Test
        @DisplayName("Слово изделия переживает разбор")
        void keepsItemName() {
            ReceivingParams params = parser.parse(
                    "поставщик гол с 12.12.2025 датчик давления");

            assertEquals("датчик давления", params.term());
        }

        @Test
        @DisplayName("Отклонённое имя не исчезает из остатка")
        void rejectedNameStaysInTerm() {
            /*
             * Имя с цифрами — это номер, а не поставщик: фильтром оно не
             * становится. Но и выбросить его значило бы потерять слова
             * запроса, — они обязаны дойти до обычного поиска.
             */
            ReceivingParams params = parser.parse(
                    "поставщик 12к30 с 12.12.2025");

            assertNull(params.supplierName());
            assertTrue(
                    params.term() != null && params.term().contains("12к30"),
                    "отклонённое имя обязано остаться в остатке: "
                            + params.term()
            );
        }
    }
}
