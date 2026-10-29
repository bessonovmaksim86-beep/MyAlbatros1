package ru.company.production.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка каталога миграций без запуска базы.
 *
 * <p>Смысл в том, чтобы ловить ошибки, которые иначе вскрываются только
 * на чистой установке (то есть не на той машине, где их допустили):
 * повтор версии, имя не по шаблону Flyway, отсутствие разделителя
 * {@code __}. Пропуски в нумерации (V49, V53) при этом легальны: Flyway
 * требует монотонности, а не непрерывности, а номера удалённых миграций
 * повторно занимать нельзя.
 *
 * <p>Тест не требует Docker и потому работает в обычной сборке.
 */
class FlywayMigrationNamingTest {

    private static final Pattern VERSIONED_NAME =
            Pattern.compile("V(\\d+)__(.+)\\.sql");

    /** Номера, занятые удалёнными из проекта миграциями. */
    private static final List<Integer> RESERVED_GAPS = List.of(49, 53);

    /** U+FFFD — символ замены, который остаётся после неверной декодировки. */
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;

    /** Файл миграций: имя + его числовая версия. */
    private record Migration(Path file, int version, String name) {
    }

    /*
     * Сортировка именно по числовой версии: по имени файла порядок
     * лексикографический (V1, V10, V11, …, V2), и проверка «версии идут
     * по возрастанию» падала бы на корректном каталоге.
     */
    private static List<Migration> migrations() {
        URL root = FlywayMigrationNamingTest.class
                .getResource("/db/migration");
        assertTrue(root != null,
                "Каталог classpath:db/migration не найден — тест больше "
                        + "не проверяет боевые миграции");

        Path directory = directoryOf(root);
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .map(FlywayMigrationNamingTest::toMigration)
                    .sorted(Comparator.comparingInt(Migration::version))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Migration toMigration(Path file) {
        String name = file.getFileName().toString();
        Matcher matcher = VERSIONED_NAME.matcher(name);
        assertTrue(matcher.matches(), "нечитаемое имя: " + name);
        return new Migration(file,
                Integer.parseInt(matcher.group(1)), name);
    }

    private static Path directoryOf(URL root) {
        try {
            /*
             * Paths.get(URL) требует OSGi-сборки, а в обычной JVM путь из
             * URL остаётся URL-кодированным: «/D:%D0%9D…» ломается на
             * Windows с IllegalCharNameException.
             */
            return Paths.get(root.toURI());
        } catch (Exception unexpected) {
            try {
                return Path.of(new URI(null, root.getPath(), null).getPath());
            } catch (Exception fallbackFailed) {
                throw new IllegalStateException(
                        "Не удалось получить путь к каталогу миграций: " + root,
                        fallbackFailed);
            }
        }
    }

    @Test
    @DisplayName("каждый файл называется V<номер>__<описание>.sql")
    void everyFileMatchesVersionedNaming() {
        List<String> bad = migrations().stream()
                .map(Migration::name)
                .filter(name -> !VERSIONED_NAME.matcher(name).matches())
                .toList();

        assertTrue(bad.isEmpty(),
                "Flyway не увидит эти файлы и молча пропустит: " + bad);
    }

    @Test
    @DisplayName("версии уникальны")
    void versionsAreUnique() {
        List<Integer> versions = migrations().stream()
                .map(Migration::version)
                .toList();

        List<Integer> duplicated = versions.stream()
                .filter(v -> versions.stream().filter(v::equals).count() > 1)
                .distinct()
                .toList();

        assertTrue(duplicated.isEmpty(),
                "одинаковый номер миграции Flyway отвергает на старте: "
                        + duplicated);
    }

    @Test
    @DisplayName("разрывы нумерации только заранее известные")
    void gapsAreOnlyTheKnownOnes() {
        List<Integer> versions = migrations().stream()
                .map(Migration::version)
                .toList();

        List<Integer> actualGaps = new ArrayList<>();
        for (int v = versions.getFirst() + 1; v < versions.getLast(); v++) {
            if (!versions.contains(v)) {
                actualGaps.add(v);
            }
        }

        assertEquals(RESERVED_GAPS, actualGaps,
                "случайный пропущенный номер — опечатка в имени файла, "
                        + "новый удалённый — его номер нельзя занимать "
                        + "повторно на уже настроенных базах");
    }

    @Test
    @DisplayName("миграции читаются как UTF-8 без символов замены")
    void migrationsAreValidUtf8() throws IOException {
        /*
         * Кириллические подписи справочников (organization_units,
         * operation_types) попадают в базу прямо из SQL. Файл, сохранённый
         * в Cp1251, применится без ошибки, но даст в базе вместо названий
         * «?????» — заметно это только в интерфейсе боевой системы.
         */
        for (Migration migration : migrations()) {
            String text =
                    Files.readString(migration.file(), StandardCharsets.UTF_8);
            int index = text.indexOf(REPLACEMENT_CHARACTER);
            assertTrue(index < 0,
                    migration.name() + ": символ замены на позиции " + index
                            + " — файл сохранён не в UTF-8");
        }
    }

    @Test
    @DisplayName("одна колонка не добавляется двумя миграциями")
    void noColumnAddedTwice() throws IOException {
        /*
         * Повторный ADD COLUMN той же колонки проходит на базе, где первую
         * миграцию уже применили, и падает на чистой установке — то есть
         * ровно там, где проект проверяют перед релизом. flyway.clean и
         * повторный прогон в CI ловят это, но только когда Docker есть;
         * проверка по тексту работает всегда.
         */
        List<String> added = new ArrayList<>();
        for (Migration migration : migrations()) {
            String sql = Files.readString(migration.file(),
                    StandardCharsets.UTF_8);
            added.addAll(addedColumnsOf(withoutComments(sql), migration.name()));
        }

        List<String> duplicated = added.stream()
                .map(entry -> entry.substring(0, entry.indexOf(" (")))
                .filter(column -> added.stream()
                        .filter(e -> e.startsWith(column + " ("))
                        .count() > 1)
                .distinct()
                .toList();

        assertTrue(duplicated.isEmpty(),
                "колонка добавляется дважды (на чистой базе вторая "
                        + "миграция упадёт с «duplicate column»): " + duplicated);
    }

    /**
     * Пары «таблица.колонка», которые миграция создаёт через
     * {@code ALTER TABLE ... ADD [COLUMN]}.
     *
     * <p>SQL режется на операторы по {@code ;}, а не ищется одним
     * жадным шаблоном: блок {@code ALTER TABLE} в проекте продолжается
     * через {@code ADD CONSTRAINT} и {@code ADD INDEX}, и между
     * операторами стоят комментарии. Шаблон, который их не учитывает,
     * приписывал колонку второй таблицы первой — и тест выдавал дубли там,
     * где их нет.
     *
     * <p>Ключевые слова {@code CONSTRAINT}, {@code INDEX}, {@code KEY} и
     * прочие отсекаются: они тоже идут после {@code ADD}, но колонками не
     * являются.
     */
    private static List<String> addedColumnsOf(String sql, String source) {
        Pattern table = Pattern.compile(
                "^\\s*ALTER\\s+TABLE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
                Pattern.CASE_INSENSITIVE);
        Pattern add = Pattern.compile(
                "\\bADD\\s+(?:COLUMN\\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?",
                Pattern.CASE_INSENSITIVE);

        Set<String> nonColumns = Set.of(
                "CONSTRAINT", "INDEX", "KEY", "PRIMARY", "UNIQUE",
                "FULLTEXT", "FOREIGN", "CHECK", "SPATIAL");

        List<String> result = new ArrayList<>();
        for (String statement : sql.split(";")) {
            Matcher tableMatcher = table.matcher(statement);
            if (!tableMatcher.find()) {
                continue;
            }
            String tableName = tableMatcher.group(1);

            Matcher addMatcher = add.matcher(statement);
            while (addMatcher.find()) {
                String column = addMatcher.group(1).toUpperCase();
                if (!nonColumns.contains(column)) {
                    result.add(tableName + "." + column + " (" + source + ")");
                }
            }
        }
        return result;
    }

    /** SQL без комментариев: они мешают резать текст на операторы. */
    private static String withoutComments(String sql) {
        return sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*", " ");
    }
}
