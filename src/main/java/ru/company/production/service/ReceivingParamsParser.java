package ru.company.production.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор поисковой фразы на параметры прихода.
 *
 * Оператор называет не всё подряд: «найди ордеры поставщика АО ГОЛ
 * с 12.12.2025 до 14.12.2025», или только поставщика, или только
 * период. Правило одно: фильтром становится то, что из фразы
 * прочитано, а непрочитанное условие не применяется (тот же приём,
 * что у {@link WarehouseSerialSearchFilter}: пустое значение значит
 * «не выбрано», а не ошибку).
 *
 * Период считается по {@code createdAt} — дате оформления партии. Из
 * трёх дат прихода она единственная заполнена у любой партии, включая
 * черновик: {@code confirmedAt} появляется после подтверждения
 * сканированием, {@code acceptedAt} — после приёмки с БВК, и фильтр по
 * ним молчал бы ровно на тех приходах, которые ищут чаще всего.
 *
 * Дата читается цифрами («12.12.2025») или числом с названием месяца
 * («12 декабря»). Порядковые числительные не разбираются: голосовой
 * ввод приводит числа к цифрам у человека в браузере (см.
 * static/js/voice-search.js), а непонятное остаётся в
 * {@link ReceivingParams#term()} и доискивается обычным поиском.
 */
@Service
public class ReceivingParamsParser {

    private static final String MONTH_WORDS =
            "январ[яю]|феврал[яю]|марта|апрел[яю]|мая|июн[яю]|июл[яю]"
                    + "|август[ау]|сентябр[яю]|октябр[яю]|ноябр[яю]|декабр[яю]";

    /**
     * Полная дата: день, месяц и год через точку, слеш или дефис.
     *
     * Все три части обязательны: без года «2026-005» из номера заказа
     * посчиталось бы датой.
     */
    private static final Pattern NUMERIC_DATE =
            Pattern.compile("(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})(?!\\d)");

    /** Дата с названием месяца; год без него понимается как текущий. */
    private static final Pattern WORD_DATE =
            Pattern.compile("(\\d{1,2})\\s+(" + MONTH_WORDS + ")(?:\\s+(\\d{4}))?");

    /**
     * Слово, после которого называют поставщика. Родительный падеж
     * стоит первым: совпадение ищется по очереди, и короткая форма
     * оставила бы в имени окончание длинной.
     */
    private static final Pattern SUPPLIER_KEYWORD = Pattern.compile(
            "поставщика|поставщик[уаом]?|производителя|производител[ьяюом]?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Предлог периода — отдельным словом перед датой. Граница слова
     * обязательна: «с» внутри «Спецмонтаж» период не начинает.
     */
    private static final Pattern PERIOD_OPEN = Pattern.compile(
            "(?:^|\\s)(?:начиная с|с|со|от|за)\\s+$", Pattern.CASE_INSENSITIVE);

    /** Предлог периода в начале имени («АО ГОЛ с 12.12» — имя до «с»). */
    private static final Pattern PERIOD_BEFORE_DATE = Pattern.compile(
            "(?:^|\\s)(?:с|со|по|до|от|за|к)\\s+(?:\\d|" + MONTH_WORDS + ")");

    /**
     * Слова вокруг параметров, не являющиеся ни поставщиком, ни датой:
     * «найди ордеры АО ГОЛ» обязано дать «АО ГОЛ».
     */
    private static final Pattern FILLER_WORDS = Pattern.compile(
            "(?:^|(?<=\\s))(?:"
                    + "найди|покажи|поищи|ищи|искать|найти"
                    + "|ордер[ауыяоев]*|заказ[ауыяоев]*|приход[ауыяоев]*"
                    + "|поступлени[яюе]*|парти[яюие]|партию|партий"
                    + "|дат[аыяеуо]|числ[аоуе]|число|числа|период[ауоме]*"
                    + "|интерфейс[ауыяе]*|у|меня|мне|мой|мои|моё|мое|моем|моём"
                    + "|в|во|на|по|из|до|к|от|с|со"
                    + "|котор[аыоеый]|это|что|где|когда"
                    + "|только|все|всё|любой|любая|любое|любых"
                    + "|созданн[аыоеы]+|оформленн[аыоеы]+"
                    + ")(?=$|\\s|[.,;:])",
            Pattern.CASE_INSENSITIVE);

    private static final String EDGE_PUNCTUATION = " .-–—,:;";

    private static final Map<String, Integer> MONTHS = months();

    private static Map<String, Integer> months() {
        Map<String, Integer> map = new LinkedHashMap<>();

        map.put("января", 1);
        map.put("февраля", 2);
        map.put("марта", 3);
        map.put("апреля", 4);
        map.put("мая", 5);
        map.put("июня", 6);
        map.put("июля", 7);
        map.put("августа", 8);
        map.put("сентября", 9);
        map.put("октября", 10);
        map.put("ноября", 11);
        map.put("декабря", 12);

        return map;
    }

    /**
     * Что удалось прочитать из фразы.
     *
     * Пустое поле значит «оператор это не называл», и фильтр по нему не
     * строится: состояние «неизвестно» записи не нужно. {@link #term()}
     * — фраза без параметров, ею ищутся остальные категории, чтобы дата
     * не попадала в LIKE как текст.
     */
    public record ReceivingParams(
            String supplierName,
            LocalDate dateFrom,
            LocalDate dateTo,
            String term
    ) {

        static final ReceivingParams NONE =
                new ReceivingParams(null, null, null, null);

        /** Назван ли хотя бы один параметр прихода. */
        public boolean hasAny() {
            return supplierName != null || dateFrom != null || dateTo != null;
        }

        public LocalDateTime fromStart() {
            return dateFrom == null ? null : dateFrom.atStartOfDay();
        }

        /*
         * Правая граница — НАЧАЛО следующих суток: «по 14.12.2025»
         * обязано включить партии, оформленные 14-го в любое время.
         * «<=» по дате отсекло бы последний день целиком, потому что
         * createdAt хранит время.
         */
        public LocalDateTime toExclusive() {
            return dateTo == null ? null : dateTo.plusDays(1).atStartOfDay();
        }
    }

    /**
     * Читает из фразы поставщика, период и остаток для обычного поиска.
     *
     * Возвращает {@link ReceivingParams#NONE}, если ни один параметр не
     * назван: вызывающий по {@link ReceivingParams#hasAny()} решает,
     * строить ли фильтр по приходам или отдать фразу штатному поиску.
     */
    public ReceivingParams parse(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return ReceivingParams.NONE;
        }

        String lower = phrase.toLowerCase(Locale.forLanguageTag("ru"));

        List<Span> dates = readDates(lower);
        Span supplier = readSupplier(lower, dates);

        if (dates.isEmpty() && supplier == null) {
            return ReceivingParams.NONE;
        }

        LocalDate from = dates.isEmpty() ? null : dates.get(0).date();
        LocalDate to = dates.size() > 1 ? dates.get(1).date() : null;

        /*
         * Одна дата без предлога — назван КОНКРЕТНЫЙ день, и правая
         * граница обязана совпасть с левой: иначе из одного дня вышел бы
         * перечень «от этой даты и далее».
         */
        if (from != null && to == null && !opensPeriod(lower, dates.get(0))) {
            to = from;
        }

        return new ReceivingParams(
                supplier == null ? null : clean(supplier.text(lower)),
                from,
                to,
                residual(lower, dates, supplier)
        );
    }

    /* ============================================================
       ДАТЫ
       ============================================================ */

    /*
     * Даты по порядку упоминания: первая — начало периода, вторая —
     * его конец. Оба шаблона разбираются совместно и сортируются по
     * позиции, иначе «с 12 декабря 2025 по 14.12.2025» дала бы два
     * одинаковых числа: порядок задаёт оператор, а не шаблон разбора.
     */
    private List<Span> readDates(String lower) {
        List<Span> found = new ArrayList<>();

        Matcher numeric = NUMERIC_DATE.matcher(lower);

        while (numeric.find()) {
            LocalDate date = safeDate(
                    Integer.parseInt(numeric.group(3)),
                    Integer.parseInt(numeric.group(2)),
                    Integer.parseInt(numeric.group(1)));

            if (date != null) {
                found.add(new Span(numeric.start(), numeric.end(), date));
            }
        }

        Matcher words = WORD_DATE.matcher(lower);

        while (words.find()) {
            Integer month = MONTHS.get(words.group(2));

            if (month == null || overlaps(found, words.start(), words.end())) {
                continue;
            }

            int year = words.group(3) == null
                    ? LocalDate.now().getYear()
                    : Integer.parseInt(words.group(3));

            LocalDate date = safeDate(year, month,
                    Integer.parseInt(words.group(1)));

            if (date != null) {
                found.add(new Span(words.start(), words.end(), date));
            }
        }

        found.sort(Comparator.comparingInt(Span::start));

        return found;
    }

    /** Словесная дата не должна дублировать уже прочитанную числом. */
    private boolean overlaps(List<Span> found, int start, int end) {
        return found.stream()
                .anyMatch(span -> start < span.end() && span.start() < end);
    }

    /*
     * Дата без исключения: «13.14.2025» и «31.02.2025» — не дата, а
     * набор цифр (например, часть номера), и ронять из-за него разбор
     * всей фразы нельзя.
     */
    private LocalDate safeDate(int year, int month, int day) {
        if (year < 1990 || year > 2100 || month < 1 || month > 12
                || day < 1) {
            return null;
        }

        return day > LocalDate.of(year, month, 1).lengthOfMonth()
                ? null
                : LocalDate.of(year, month, day);
    }

    /**
     * Стоит ли перед датой предлог начала периода.
     *
     * Смотрим хвост текста ПЕРЕД совпадением, а не всю фразу: «АО ГОЛ с
     * 12.12.2025» — период, «12.12.2025» само по себе — конкретный день.
     */
    private boolean opensPeriod(String lower, Span date) {
        String head = lower.substring(0, date.start());

        return PERIOD_OPEN.matcher(
                head.substring(Math.max(0, head.length() - 14))).find();
    }

    /* ============================================================
       ПОСТАВЩИК
       ============================================================ */

    /*
     * Имя — кусок фразы между ключевым словом и границей следующего
     * параметра. Граница ищется по ПОЗИЦИЯМ, а не вырезается жадной
     * регуляркой: имя само состоит из слов («АО ГОЛ», «Уралэнерго»), и
     * шаблон «ключ + слова + предлог» схлопнул бы его до последнего
     * слова.
     */
    private Span readSupplier(String lower, List<Span> dates) {
        Matcher keyword = SUPPLIER_KEYWORD.matcher(lower);
        boolean named = keyword.find();

        int from = named ? afterKeyword(lower, keyword.end()) : 0;
        int end = named ? namedBoundary(lower, from, dates)
                : keywordlessBoundary(lower, dates);

        /*
         * Без ключевого слова имя обязано быть похожим на наименование:
         * два символа перед датой — обрывок («го», «итак»), и выдавать
         * его за поставщика нельзя.
         */
        if (end <= from || (!named && end - from < 3)) {
            return null;
        }

        return new Span(from, end, null);
    }

    /** Начало имени: за словом «поставщик» и опциональным предлогом. */
    private int afterKeyword(String lower, int keywordEnd) {
        String tail = lower.substring(keywordEnd);
        Matcher leading = Pattern
                .compile("^\\s+(?:(?:у|в|на|от|из|для)\\s+)?")
                .matcher(tail);

        return keywordEnd + (leading.find() ? leading.end() : 0);
    }

    /** Конец имени: предлог периода или первая дата после него. */
    private int namedBoundary(String lower, int from, List<Span> dates) {
        int end = lower.length();

        Matcher boundary = PERIOD_BEFORE_DATE.matcher(lower);

        if (boundary.find(from)) {
            /* Имя кончается на предлоге, но сам предлог в него не входит. */
            end = from + boundary.start() + leadingSpaces(boundary.group());
        }

        for (Span date : dates) {
            int start = opensPeriod(lower, date)
                    ? withPreposition(lower, date.start())
                    : date.start();

            if (start >= from) {
                end = Math.min(end, start);
            }
        }

        return end;
    }

    /*
     * Конец имени, когда слово «поставщик» не прозвучало.
     *
     * Имя берётся из начала фразы и только если далее идёт период: без
     * ключевого слова и без предлога выдумывать поставщика из
     * произвольных слов значило бы ломать поиск по наименованию
     * («датчик давления» — не поставщик).
     */
    private int keywordlessBoundary(String lower, List<Span> dates) {
        for (Span date : dates) {
            if (opensPeriod(lower, date)) {
                return withPreposition(lower, date.start());
            }
        }

        return -1;
    }

    /** Начало предлога периода, стоящего прямо перед датой. */
    private int withPreposition(String lower, int position) {
        String head = lower.substring(0, position);
        String tail = head.substring(Math.max(0, head.length() - 14));
        Matcher open = PERIOD_OPEN.matcher(tail);

        return open.find() ? position - (tail.length() - open.start())
                : position;
    }

    private int leadingSpaces(String value) {
        int index = 0;

        while (index < value.length()
                && Character.isWhitespace(value.charAt(index))) {
            index++;
        }

        return index;
    }

    /*
     * Черновая строка → имя поставщика.
     *
     * Служебные слова вырезаются, остаток обязан быть внятным: пустая
     * строка или одна буква поставщиком не считаются, и параметр
     * отсутствует, а не даёт выдачу «все партии подряд».
     */
    private String clean(String raw) {
        String name = strip(FILLER_WORDS.matcher(raw).replaceAll(" ")
                .replaceAll("\\s{2,}", " "));

        if (name.length() < 2) {
            return null;
        }

        /*
         * Имя поставщика — буквы. Цифры внутри означают, что вырезан
         * номер ордера или изделия («ЗП 2026 005»), а такие строки
         * находит обычный поиск по номеру.
         */
        return name.chars().anyMatch(Character::isDigit) ? null : name;
    }

    private String strip(String value) {
        String text = value.trim();
        int from = 0;
        int to = text.length();

        while (from < to && EDGE_PUNCTUATION.indexOf(text.charAt(from)) >= 0) {
            from++;
        }

        while (to > from
                && EDGE_PUNCTUATION.indexOf(text.charAt(to - 1)) >= 0) {
            to--;
        }

        return text.substring(from, to).trim();
    }

    /* ============================================================
       ОСТАТОК ФРАЗЫ
       ============================================================ */

    /*
     * Фраза без параметров — тем ищутся остальные категории.
     *
     * Даты вместе с их предлогами и имя поставщика вырезаются, чтобы
     * LIKE не искал «12.12.2025» в наименованиях и чтобы имя не удваивало
     * фильтр. Если осталась пустота, терма нет: поиск идёт только по
     * параметрам, и «показать всё» вместо ответа недопустимо.
     */
    private String residual(String lower, List<Span> dates, Span supplier) {
        List<Span> cuts = new ArrayList<>(dates);

        if (supplier != null) {
            cuts.add(supplier);
        }

        StringBuilder rest = new StringBuilder(lower);

        cuts.sort(Comparator.comparingInt(Span::start).reversed());

        for (Span span : cuts) {
            int start = span.date() == null
                    ? span.start()
                    : withPreposition(lower, span.start());

            rest.replace(Math.min(start, span.start()), span.end(), " ");
        }

        String cleaned = strip(FILLER_WORDS.matcher(rest).replaceAll(" ")
                .replaceAll("\\s{2,}", " "));

        return cleaned.length() >= 2 ? cleaned : null;
    }

    /** Найденный фрагмент фразы: границы и, для даты, её значение. */
    private record Span(int start, int end, LocalDate date) {

        String text(String lower) {
            return lower.substring(start, end);
        }
    }
}
