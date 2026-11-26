package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.InputControlComponentForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.InputControlComponent;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlComponentService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Журнал входного контроля несерийных комплектующих.
 *
 * Раздел стоит отдельно от проверки партий ({@link InputControlController}):
 * те комплектующие не оприходываются складом как ячейки и не имеют
 * серийного номера, поэтому здесь нет ни «взятия в работу», ни карточки,
 * ни изолятора брака — только строки журнала с принятым и забракованным
 * количеством по сопроводительному документу.
 *
 * Дата контроля и вид контроля («Входной») не запрашиваются у
 * пользователя: первую проставляет база в момент внесения, второй
 * зафиксирован в сущности. Это отличает журнал от бумажного бланка,
 * где обе колонки заполняют вручную.
 *
 * Раздел открыт обеим ролям бюро, и на просмотр, и на запись (см.
 * SecurityConfig, правило /input-control/components): журнал ведёт и
 * контролёр, и мастер бюро.
 */
@Controller
@RequestMapping("/input-control/components")
@RequiredArgsConstructor
public class InputControlComponentController {

    private final InputControlComponentService journalService;
    private final UserRepository userRepository;

    /**
     * Перечень записей журнала с отбором и формой новой строки.
     *
     * Отбор читают строкой и разбирают вручную: пустой
     * {@code <option value="">} приходит пустой строкой, а не отсутствием
     * параметра, и прямое связывание с {@code Long} или датой прервало бы
     * обработку запроса. Неразобранное значение снимает условие, а не
     * роняет страницу — ссылку на срез могли передать вручную.
     */
    @GetMapping({"", "/", "/general", "/general/"})
    public String index(
            @RequestParam(name = "dateFrom", required = false) String dateFromValue,
            @RequestParam(name = "dateTo", required = false) String dateToValue,
            @RequestParam(name = "supplierId", required = false) String supplierValue,
            @RequestParam(name = "productName", required = false) String productNameValue,
            Model model
    ) {
        LocalDate dateFrom = parseDate(dateFromValue);
        LocalDate dateTo = parseDate(dateToValue);
        Long supplierId = parseId(supplierValue);
        String productName = blankToNull(productNameValue);

        model.addAttribute("activePage", "components");

        model.addAttribute(
                "components",
                journalService.findFiltered(
                        startOfDay(dateFrom),
                        endOfDay(dateTo),
                        supplierId,
                        productName
                )
        );

        /*
         * Условия отбора возвращают в модель отдельными атрибутами:
         * форма поиска на перечне обязана показать то, что уже
         * применили, иначе страница «забывала» срез после его
         * применения.
         */
        model.addAttribute("filterDateFrom", dateFromValue);
        model.addAttribute("filterDateTo", dateToValue);
        model.addAttribute("filterSupplierId", supplierId);
        model.addAttribute("filterProductName", productName);
        model.addAttribute(
                "searchApplied",
                dateFrom != null
                        || dateTo != null
                        || supplierId != null
                        || productName != null
        );

        model.addAttribute("suppliers", journalService.findAllSuppliers());

        return "input-control/components/index";
    }

    /**
     * Форма новой записи журнала.
     *
     * Отдельная страница, а не свёрнутый блок над перечнем: строка
     * журнала содержит больше десятка реквизитов, и ради одного
     * нового номера нельзя перечерчивать весь журнал.
     */
    @GetMapping("/new")
    public String newEntry(Model model) {
        model.addAttribute("activePage", "components");
        model.addAttribute(
                "inputControlComponentForm",
                new InputControlComponentForm()
        );
        model.addAttribute("suppliers", journalService.findAllSuppliers());

        return "input-control/components/new";
    }

    /**
     * Форма правки существующей строки журнала.
     *
     * Открывается отдельным адресом, потому что перечень и форма
     * живут на разных экранах: строку правят по ссылке из таблицы, и
     * контролёру не нужен весь перечень ради одной записи.
     */
    @GetMapping("/edit/{id}")
    public String edit(
            @PathVariable("id") Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        InputControlComponent component;

        try {
            component = journalService.findDetailed(id);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

            return "redirect:/input-control/components";
        }

        model.addAttribute("activePage", "components");
        model.addAttribute("editing", component);
        model.addAttribute(
                "inputControlComponentForm",
                journalService.formFrom(component)
        );
        model.addAttribute("suppliers", journalService.findAllSuppliers());

        return "input-control/components/edit";
    }

    /**
     * Сохраняет строку журнала: новую или исправленную.
     *
     * Идентификатор лежит в форме, а не в адресе: одна отправка
     * закрывает и создание, и правку, поэтому перечень не обязан
     * различать два действия.
     *
     * При отказе страница возвращается формой с уже введёнными
     * значениями, а не redirect'ом: реквизитов много, и терять их из-за
     * одного пропущенного поставщика — значит заставлять контролёра
     * вводить строку заново.
     */
    @PostMapping("/save")
    public String save(
            @Valid @ModelAttribute("inputControlComponentForm") InputControlComponentForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        boolean isEdit = form.idAsLong() != null;

        if (!errors.hasErrors()) {
            try {
                journalService.save(form, currentUser(authentication));

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        isEdit
                                ? "Запись журнала обновлена"
                                : "Запись добавлена в журнал входного контроля"
                );

                /*
                 * Правку возвращают в перечень, а не на форму: строка
                 * сохранена, и держать контролёра в карточке после
                 * записи — значит заставлять его выходить вручную.
                 */
                return "redirect:/input-control/components";
            } catch (
                    EntityNotFoundException
                    | IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject("components.save", exception.getMessage());
            }
        }

        /*
         * Ошибку возвращают той же страницей, где была отправка: при
         * создании это перечень, при правке — форма редактирования.
         * Справочник поставщика подкладывают заново в обоих случаях,
         * иначе выпадающий список остался бы пустым.
         */
        model.addAttribute("activePage", "components");
        model.addAttribute("suppliers", journalService.findAllSuppliers());
        model.addAttribute(
                "errorMessage",
                errors.getAllErrors().get(0).getDefaultMessage()
        );

        if (isEdit) {
            /*
             * Запись могли удалить, пока форма лежала открытой: отказ
             * возвращает её тем же экраном, и повторный поиск той же
             * строки бросил бы 500-ю там, где достаточно сообщения.
             */
            try {
                model.addAttribute(
                        "editing",
                        journalService.findDetailed(form.idAsLong())
                );
            } catch (EntityNotFoundException exception) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/input-control/components";
            }

            return "input-control/components/edit";
        }

        /*
         * Отказ при создании возвращает форму новой записи, а не
         * перечень: введённые реквизиты обязаны сохраниться, иначе
         * строку журнала пришлось бы вводить заново.
         */
        return "input-control/components/new";
    }

    /**
     * Удаляет строку журнала.
     *
     * POST, а не GET: удаление необратимо, и ссылка на него не должна
     * открываться предпросмотром или повторяться кнопкой «назад».
     * Возврат — redirect'ом с flash-сообщением, чтобы повторный F5 не
     * удалял следующую строку.
     */
    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable("id") Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            journalService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Запись удалена из журнала"
            );
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:/input-control/components";
    }

    /**
     * Начало суток для границы «с»: строка этого дня входит в отбор.
     */
    private static LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    /**
     * Начало суток «после» для границы «по»: последний день периода
     * входит в выборку целиком, хотя {@code control_date} хранит метку
     * времени с часами.
     */
    private static LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    /** Дата поля ввода: неразобранное значение снимает условие, а не роняет страницу. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Идентификатор из строки запроса: пусто или не число — «не выбрано». */
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Контролёр строки журнала: запись подписывают сотрудником, который
     * её внёс, — по этой строке позже сверяют журнал с документом.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись контролёра не найдена"
                ));
    }
}
