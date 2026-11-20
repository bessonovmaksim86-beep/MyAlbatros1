package ru.company.production.controller;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.AppUser;
import ru.company.production.service.InputControlComponentService;
import ru.company.production.service.InputControlJournalParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Внесение данных входного контроля из файла Excel.
 *
 * Раздел администрирования: администратор загружает лист журнала,
 * выбирает исполнителя службы качества и проводит строки в базу.
 * Обработкой записей занят тот же
 * {@link InputControlComponentService}, что и ручным внесением в
 * бюро, поэтому импорт не может завести строку, которую отказалась
 * бы принять форма контролёра.
 *
 * Проведение идёт в два шага — предпросмотр и подтверждение. Лист
 * разбирается без записи в базу и показывается администратору:
 * раскладку файла задают вручную, и пока он не увидит, в какие
 * колонки что попало, нельзя ручаться за результат. Отказ на
 * подтверждении обошёлся бы дороже — половину строк база уже
 * приняла бы.
 *
 * Разобранный лист живёт в сессии, как предпросмотр чек-листа в
 * диспетчерской: файл передавать повторно после выбора исполнителя
 * незачем, а держать его в памяти запроса нельзя — страницы
 * предпросмотра и подтверждения это разные запросы.
 *
 * Исполнитель обязателен и выбирается из сотрудников обоих бюро:
 * строку журнала подписывает тот, кто вёл контроль, а не
 * администратор, нажавший кнопку.
 */
@Controller
@RequestMapping("/admin/input-control-import")
@RequiredArgsConstructor
public class AdminInputControlImportController {

    /** Ключ сессии с разобраным листом журнала. */
    private static final String PREVIEW_ATTRIBUTE =
            "inputControlJournalPreview";

    private final InputControlJournalParser journalParser;
    private final InputControlComponentService journalService;

    /**
     * Форма загрузки: выбор файла и исполнителя.
     *
     * Когда предпросмотр уже есть, страница показывает его вместо
     * пустой формы — иначе администратор, вернувшись по «назад»,
     * потерял бы разобранный лист.
     */
    @GetMapping({"", "/"})
    public String index(HttpSession session, Model model) {
        model.addAttribute("activePage", "inputControlImport");
        model.addAttribute(
                "executors",
                journalService.findQualityExecutors()
        );
        model.addAttribute("preview", readPreview(session));

        /*
         * Имя разобранного листа: в книге их несколько (архив,
         * актуальный журнал, расчётный лист), и в предпросмотре
         * должно быть видно, какой именно взят.
         */
        model.addAttribute(
                "previewSheet",
                session.getAttribute("inputControlJournalSheet")
        );

        /*
         * Исполнителя показываем и на форме, и в скрытом поле
         * подтверждения: сессия хранит его между шагами, поэтому
         * при возврате «назад» выбор не теряется.
         *
         * Кладём числом, а не строкой из сессии: шаблон сверяет его
         * с идентификатором сотрудника, и сравнение Long со строкой
         * никогда не совпало бы — список всегда рисовался бы без
         * выбранного варианта.
         */
        Long selectedExecutorId =
                parseId((String) session.getAttribute("inputControlJournalExecutor"));

        if (selectedExecutorId != null) {
            model.addAttribute("selectedExecutorId", selectedExecutorId);
        }

        return "admin/input-control-import/index";
    }

    /**
     * Очищает сессию предпросмотра и исполнителя.
     *
     * Нужен для кнопки «Отменить предпросмотр»: пользователь
     * возвращается к пустой форме и может загрузить другой файл.
     */
    @GetMapping("/clear")
    public String clear(HttpSession session) {
        session.removeAttribute(PREVIEW_ATTRIBUTE);
        session.removeAttribute("inputControlJournalSheet");
        session.removeAttribute("inputControlJournalExecutor");

        return "redirect:/admin/input-control-import";
    }

    /**
     * Разбирает файл и отдаёт лист на предпросмотр.
     *
     * Принимаются оба формата Excel: .xlsx (OOXML) и .xls (BIFF),
     * так как файл могут сохранить из любой версии.
     */
    @PostMapping("/upload")
    public String upload(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "executorId", required = false) String executorValue,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Выберите файл журнала (.xls или .xlsx)"
            );

            return "redirect:/admin/input-control-import";
        }

        if (!isExcel(file.getOriginalFilename())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Журнал принимают файлом Microsoft Excel (.xls или .xlsx)"
            );

            return "redirect:/admin/input-control-import";
        }

        List<InputControlJournalParser.ParsedJournalRow> rows;
        String sheetName;

        try {
            InputControlJournalParser.ParsedJournal parsed =
                    journalParser.parse(file);
            rows = parsed.rows();
            sheetName = parsed.sheetName();
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

            return "redirect:/admin/input-control-import";
        }

        session.setAttribute(PREVIEW_ATTRIBUTE, rows);
        session.setAttribute("inputControlJournalSheet", sheetName);

        /*
         * Исполнителя сохраняют между шагами: иначе после загрузки
         * файла выпадающий список опустел бы, и выбор пришлось бы
         * делать заново — ровно то, из-за чего предпросмотр
         * неудобно смотреть построчно.
         */
        if (executorValue != null && !executorValue.isBlank()) {
            session.setAttribute("inputControlJournalExecutor", executorValue.trim());
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Разобрано строк: " + rows.size()
                        + ". Проверьте предпросмотр и подтвердите внесение"
        );

        return "redirect:/admin/input-control-import";
    }

    /**
     * Проводит разобранный лист в базу.
     *
     * Отказ по одной строке не откатывает остальные: лист журнал
     * ведут месяцами, и из-за одной опечатки в строке 40 не следует
     * отменять сорок верных записей. Ошибочные строки собираются в
     * сообщение, и их число администратор видит рядом с числом
     * внесённых.
     *
     * Сохранение идёт в одной транзакции сервиса на каждую строку:
     * строка журнала — самостоятельная запись, она не образует с
     * соседями один документ.
     */
    @PostMapping("/confirm")
    public String confirm(
            @RequestParam(name = "executorId", required = false) String executorValue,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        @SuppressWarnings("unchecked")
        List<InputControlJournalParser.ParsedJournalRow> rows =
                (List<InputControlJournalParser.ParsedJournalRow>)
                        session.getAttribute(PREVIEW_ATTRIBUTE);

        if (rows == null || rows.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Лист не загружен: выберите файл и дождитесь предпросмотра"
            );

            return "redirect:/admin/input-control-import";
        }

        Long executorId = parseId(executorValue);

        if (executorId == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Выберите исполнителя службы качества"
            );

            return "redirect:/admin/input-control-import";
        }

        AppUser executor;

        try {
            executor = journalService.requireQualityExecutor(executorId);
        } catch (
                jakarta.persistence.EntityNotFoundException
                | IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

            return "redirect:/admin/input-control-import";
        }

        int saved = 0;
        List<String> failures = new ArrayList<>();

        for (InputControlJournalParser.ParsedJournalRow row : rows) {
            try {
                /*
                 * Пустая колонка A заполняется моментом внесения
                 * внутри сервиса: строка без даты не попала бы ни в
                 * один отбор по периоду, и отказ из-за неё лишь
                 * затруднил бы перенос старого журнала.
                 */
                journalService.saveImported(row, executor);

                saved++;
            } catch (
                    IllegalArgumentException
                    | IllegalStateException exception
            ) {
                failures.add("строка " + row.lineNo() + ": " + exception.getMessage());
            }
        }

        session.removeAttribute(PREVIEW_ATTRIBUTE);
        session.removeAttribute("inputControlJournalExecutor");

        if (saved == 0) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Не внесено ни одной строки. " + String.join("; ", failures)
            );

            return "redirect:/admin/input-control-import";
        }

        if (failures.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "В журнал внесено строк: " + saved
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Внесено строк: " + saved
                            + "; отклонено: " + failures.size()
                            + " (" + String.join("; ", failures) + ")"
            );
        }

        return "redirect:/admin/input-control-import";
    }

    /** Разобранный лист из сессии либо {@code null}. */
    private List<InputControlJournalParser.ParsedJournalRow> readPreview(
            HttpSession session
    ) {
        Object preview = session.getAttribute(PREVIEW_ATTRIBUTE);

        @SuppressWarnings("unchecked")
        List<InputControlJournalParser.ParsedJournalRow> rows =
                preview instanceof List<?> list
                        ? (List<InputControlJournalParser.ParsedJournalRow>) list
                        : null;

        return rows;
    }

    /**
     * Проверка расширения по имени файла.
     *
     * Принимаются оба формата Excel: .xlsx и старый .xls — журнал
     * ведут годами и пересохраняют в ту версию, что стоит на
     * машине. Имя берётся из формы и могло прийти с путём, поэтому
     * сравнивают окончание после последней точки, а не всю строку.
     */
    private static boolean isExcel(String filename) {
        if (filename == null) {
            return false;
        }

        String name = filename.replaceAll(".*[/\\\\]", "").toLowerCase();

        return name.endsWith(".xlsx") || name.endsWith(".xls");
    }

    /** Идентификатор из формы: пусто или не число — {@code null}. */
    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
