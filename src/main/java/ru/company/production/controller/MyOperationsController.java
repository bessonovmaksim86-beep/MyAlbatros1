package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.AppUser;
import ru.company.production.dto.ControlParamCompletionForm;
import ru.company.production.dto.ControlParamValueForm;
import ru.company.production.dto.ControlParamView;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.RepairKittingGroup;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.BvkAcceptanceService;
import ru.company.production.service.CellReceivingService;
import ru.company.production.service.CustomerOrderSuspensionService;
import ru.company.production.service.FinishedProductClosureService;
import ru.company.production.service.InputControlService;
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.OperationControlParamService;
import ru.company.production.service.RepairKittingOperationService;
import ru.company.production.service.WorkshopDistributionService;
import ru.company.production.service.WorkshopStaffService;

import java.util.List;

/**
 * Работы, закреплённые за текущим пользователем.
 *
 * Адрес вынесен за пределы {@code /workshop/**} намеренно: тот путь
 * закрыт перед исполнителями, а «Мои операции» — раздел именно
 * исполнителя. Оставлять его внутри цехового модуля означало бы
 * открыть весь модуль роли, которой он не принадлежит.
 *
 * Руководителю подразделения к его собственным работам добавляются
 * операции получения комплектации: их планировщик закрепляет за
 * подразделением, а не за человеком, и в очереди распределения они
 * не участвуют — переложить их не на кого.
 *
 * Граница определяется учётной записью, а не параметром запроса:
 * пользователь видит только то, что закреплено за ним.
 */
@Controller
@RequestMapping("/my-operations")
@RequiredArgsConstructor
public class MyOperationsController {

    /*
     * Значение параметра режима, включающее половину групповых ЗНП.
     *
     * Имя совпадает с именем кнопки в переключателе распределения, чтобы
     * два раздела одного руководителя читались одинаково.
     */
    private static final String GROUP_MODE = "group";

    /** Режим обычной половины — значение по умолчанию и для пустого параметра. */
    private static final String REGULAR_MODE = "regular";

    private final WorkshopDistributionService distributionService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;
    private final CustomerOrderSuspensionService suspensionService;
    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;

    /*
     * Проведение на СГП закрывает заказы-комплектующие, все датчики
     * которых дошли до склада готовой продукции. Хук стоит здесь, а не
     * внутри сервиса распределения: проведение — событие цеха, а
     * закрытие ЗНП — событие производства, и смешивать их в одном
     * сервисе незачем.
     */
    private final FinishedProductClosureService closureService;

    /*
     * Комплектование ремонтных ЗНП. Сервис спрашивает право по
     * привязкам сотрудника и ролям бюро, поэтому контроллер не
     * различает «исполнитель» и «руководитель»: граница одна на список
     * и на проведение, и держать её в двух местах значило бы
     * рассинхронизировать её при первой правке.
     */
    private final RepairKittingOperationService repairKittingService;

    /*
     * Счётчики пунктов модуля бюро в меню его руководителя: тот же
     * перечень партий, что видит исполнитель бюро.
     */
    private final InputControlService inputControlService;

    private final OperationControlParamService controlParamService;

    /**
     * Список работ текущего пользователя.
     *
     * Режим задаётся адресом, а не сессией: {@code ?mode=group} отдаёт
     * половину групповых ЗНП, пустой параметр — обычную. Работы обеих
     * половин закреплены за одним человеком и проводятся одной кнопкой,
     * но подписываются разными номерами: у обычного ЗНП это серийник
     * выпуска, у группового — номер ячейки, внесённый складом. Держать
     * их в одной таблице нельзя — колонка номера врёт либо там, либо
     * там, — поэтому вверху стоит переключатель, а адрес каждого режима
     * остаётся закладкой.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "mode", required = false) String mode,
            Authentication authentication,
            Model model
    ) {
        AppUser user = currentUser(authentication);
        boolean groupMode = GROUP_MODE.equalsIgnoreCase(safeMode(mode));

        model.addAttribute("groupMode", groupMode);
        /*
         * Режим для переключателя отдельным атрибутом: фрагмент ждёт
         * готовую строку 'regular' / 'group', и выбирать её в шаблоне
         * тернарником значило бы держать условие режима в двух местах.
         */
        model.addAttribute(
                "operationMode",
                groupMode ? GROUP_MODE : REGULAR_MODE
        );
        model.addAttribute(
                "operations",
                groupMode
                        ? distributionService.findMyGroupOperations(user)
                        : distributionService.findMyOperations(user)
        );

        /*
         * Цифры обеих кнопок готовит ModuleMenuCountersViewAdvice: тот же
         * расчёт держит пункт меню, и две цифры из одного запроса не
         * расходятся — переключатель и меню всегда показывают один итог.
         */

        /*
         * Ремонтные ЗНП в работе, сгруппированные по комплектовочной
         * операции бюро. Раздел живёт в «Мои операции» намеренно: заказ
         * ведёт исполнитель бюро, а его рабочий адрес — именно этот, и
         * пересылать человека за составом в /input-control/** значило бы
         * вернуть его в кабинет, закрытый перед его ролью.
         *
         * Пустой список для всех прочих ролей — норма: подбор держится
         * на привязках и ролях бюро, а не на пустом фильтре здесь.
         */
        List<RepairKittingGroup> kittingGroups =
                repairKittingService.findMyKittingGroups(user);

        model.addAttribute("kittingGroups", kittingGroups);
        model.addAttribute(
                "kittingOrderCount",
                kittingGroups.stream()
                        .mapToLong(RepairKittingGroup::orderCount)
                        .sum()
        );
        return decorate(user, "my-operations/index", model);
    }

    /**
     * Подписи бокового меню и верхней панели.
     *
     * Общие для списка работ и формы комплектования: обе страницы
     * рисуют то же меню своего модуля, и готовить его в двух местах
     * означало бы потерять пункт меню на одной из страниц при первой же
     * правке другой.
     */
    private String decorate(AppUser user, String view, Model model) {
        model.addAttribute("userDisplayName", displayName(user));
        model.addAttribute(
                "userPosition",
                user.getSpecialty() == null || user.getSpecialty().isBlank()
                        ? "Должность не указана"
                        : user.getSpecialty()
        );
        model.addAttribute(
                "supervisor",
                distributionService.isSupervisor(user)
        );
        boolean supervisor = distributionService.isSupervisor(user);
        String ownModule =
                ModuleRoutes.destinationFor(user.getRole()).name();

        model.addAttribute("workspaceName", ownModule);
        model.addAttribute(
                "workshopName",
                supervisor
                        ? staffService.scopeName(user)
                        : ownModule
        );

        /*
         * Раздел живёт вне /workshop/**, но рисует то же боковое меню,
         * что и модуль руководителя, поэтому подписи рода подразделения
         * готовятся здесь: для руководителя — по его подразделению,
         * для исполнителя — по модулю его роли, иначе в меню появилось
         * бы «Руководитель цеха» над списком кладовщика.
         */
        WorkshopStaffService.UnitKind unitKind =
                staffService.unitKind(user);

        model.addAttribute("unitWord", unitKind.getWord());
        model.addAttribute("unitGenitive", unitKind.getGenitive());
        model.addAttribute(
                "moduleTitle",
                supervisor ? unitKind.getModuleTitle() : ownModule
        );
        model.addAttribute(
                "fullModule",
                staffService.hasFullModule(user)
        );

        /*
         * Право видеть своё подразделение и счётчики его пунктов.
         *
         * Меню «Моих операций» берётся у цехового (или бордового) модуля,
         * а его пункты «Сотрудники», «Операции и рабочие места» и
         * «Производственный календарь» закрыты условием canViewUnit.
         * Проставляет его WorkshopViewAdvice — но тот подключён только к
         * контроллерам /workshop/**, а этот раздел живёт отдельно, и без
         * собственных признаков меню здесь сжималось до «Распределения
         * работ» и «Моих операций»: th:if над неопределённым атрибутом
         * просто не рисует пункт.
         */
        boolean canViewUnit = staffService.canViewUnit(user);
        List<Long> scope = canViewUnit
                ? staffService.unitScope(user)
                : List.of();

        model.addAttribute("canViewUnit", canViewUnit);
        model.addAttribute(
                "staffCount",
                canViewUnit ? staffService.findEmployees(scope).size() : 0
        );
        model.addAttribute(
                "unitOperationCount",
                canViewUnit ? staffService.countUnitOperations(scope) : 0L
        );

        model.addAttribute(
                "isSKGeneral",
                user.getRole() == ru.company.production.entity.Role.SK_GENERAL
        );

        /*
         * Пункты модуля бюро в меню его сотрудников: раздел рисует то же
         * боковое меню, что и модуль руководителя, и признак готовится
         * здесь — иначе и мастер, и контролёр бюро теряли бы на «Мои
         * операции» ссылки на партии своего бюро.
         *
         * Признак ставится обеим ролям бюро, а не только руководителю:
         * контролёр приходит в раздел из меню /input-control, где у него
         * «Обзор бюро», «Входной контроль», «Возврат» и «Изолятор», и без
         * тех же пунктов здесь его обратный путь обрывался — назад он
         * возвращался бы через чужое администрирование.
         */
        boolean bvkModule =
                user.getRole()
                        == ru.company.production.entity.Role.BVK_MASTER
                        || user.getRole()
                        == ru.company.production.entity.Role.BVK_EXECUTOR;

        model.addAttribute("bvkModule", bvkModule);

        if (bvkModule) {
            model.addAttribute(
                    "inWorkCount", inputControlService.countInWork()
            );
            model.addAttribute(
                    "returnCount", inputControlService.countReturns()
            );
            model.addAttribute(
                    "quarantineCount", inputControlService.countQuarantine()
            );
        }
        /*
         * Верхняя панель общего модуля подписывает пользователя по
         * карточке (ФИО и специальность), а не по логину, поэтому
         * учётная запись передаётся в модель — иначе в шапке вместо
         * «Иванов И.» оказался бы логин учётной записи.
         */
        model.addAttribute("currentUser", user);
        model.addAttribute("activePage", "my-operations");

        /*
         * Боковое меню раздел берёт у того модуля, в который роль входит
         * по входу в систему: у кладовщика это склад, и без складских
         * подписей и счётчиков он потерял бы свой модуль — меню показалось
         * бы цеховым, а ссылка «Обзор склада» осталась бы в меню цеха.
         *
         * Счётчики читаются только для складской роли: меню рисуется на
         * каждом экране, и два запроса ради цифр, которые исполнитель
         * цеха не увидит, стоили бы замедления ему.
         */
        if (ModuleRoutes.isWarehouse(user.getRole())) {
            model.addAttribute("warehouseModule", true);
            model.addAttribute(
                    "warehouseKind",
                    ModuleRoutes.warehouseKind(user.getRole())
            );
            model.addAttribute(
                    "pendingReceivingCount",
                    receivingService.countPending()
            );
            model.addAttribute(
                    "awaitingAcceptanceCount",
                    acceptanceService.countAwaiting()
            );
        } else {
            model.addAttribute("warehouseModule", false);
        }

        return view;
    }

    /**
     * Проведение экземпляра операции исполнителем.
     *
     * Форма отправляет {@code rowId} — идентификатор строки графика,
     * а не группы: позиция на десять изделий закрывается десятью
     * отдельными фактами. Кто именно проводит, берётся из учётной
     * записи — параметр пользователя в запросе открыл бы возможность
     * закрыть чужую работу.
     *
     * Проведение невозможно, если заказ покупателя приостановлен
     * (миграция V46): проверка выполняется до обращения к сервису, а
     * её текст возвращается в этот же список — исполнитель видит,
     * какой заказ стоит на паузе, а не безличную ошибку.
     *
     * Ошибка возвращается тем же списком с сообщением: страница одна,
     * и уходить с неё после неудачи незачем.
     */
    /**
     * Форма проведения экземпляра с отметкой контролируемых параметров.
     *
     * Открывается, когда у операции техпроцесса есть контролируемые
     * параметры: исполнитель обязан внести значение каждого, а
     * обязательное не может остаться пустым.
     */
    @GetMapping("/complete/{rowId}")
    public String completeForm(
            @PathVariable Long rowId,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = currentUser(authentication);
        List<ControlParamView> params;

        try {
            params = controlParamService.loadForRow(rowId);
        } catch (RuntimeException ex) {
            redirectError(redirectAttributes, ex);
            return "redirect:/my-operations";
        }

        if (params.isEmpty()) {
            return "redirect:/my-operations";
        }

        model.addAttribute("controlParams", params);
        model.addAttribute("rowId", rowId);
        model.addAttribute("completionForm", new ControlParamCompletionForm());

        return decorate(user, "my-operations/complete", model);
    }

    /**
     * Проведение экземпляра с отмеченными контролируемыми параметрами.
     *
     * Отметки приходят списком по индексу строки формы, поэтому
     * порядок может содержать пропуски: сервис сопоставляет значение
     * с параметром по его идентификатору, а не по позиции.
     */
    @PostMapping("/complete/{rowId}")
    public String completeWithControlParams(
            @PathVariable Long rowId,
            @ModelAttribute("completionForm") ControlParamCompletionForm completionForm,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = currentUser(authentication);

        try {
            suspensionService.assertRowOperable(rowId);

            List<ControlParamValueForm> values = completionForm == null
                    ? List.of()
                    : completionForm.getValues();

            controlParamService.completeWithControlParams(rowId, user, values);
            closureService.onRowCompleted(rowId);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Экземпляр проведён"
            );
        } catch (RuntimeException ex) {
            redirectError(redirectAttributes, ex);
            return "redirect:/my-operations/complete/" + rowId;
        }

        return "redirect:/my-operations";
    }

    /* Сообщение об отказе проведения — тем же списком, откуда
     * исполнитель пришёл в форму. */
    private static void redirectError(
            RedirectAttributes redirectAttributes,
            RuntimeException ex
    ) {
        redirectAttributes.addFlashAttribute(
                "errorMessage",
                ex.getMessage() == null
                        ? "Не удалось провести экземпляр"
                        : ex.getMessage()
        );
    }

    @PostMapping("/complete")
    public String complete(
            @RequestParam Long rowId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = currentUser(authentication);

        /*
         * Операция с контролируемыми параметрами проводится только
         * через форму отметки: прямая отправка из списка закрыла бы
         * работу, не зафиксировав ни одного значения, — а именно
         * значения и требует техпроцесс. Проверка держится на
         * сервере, потому что ссылка формы известна и без кнопки.
         */
        if (controlParamService.hasControlParams(rowId)) {
            return "redirect:/my-operations/complete/" + rowId;
        }

        try {
            suspensionService.assertRowOperable(rowId);
            distributionService.completeRow(rowId, user);

            /*
             * Хук СГП выполняется после проведения: completeRow уже
             * записал дату факта, и закрытие видит актуальное состояние
             * графика. Для всех прочих подразделений вызов ничего не
             * делает.
             */
            closureService.onRowCompleted(rowId);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Экземпляр проведён"
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage() == null
                            ? "Не удалось провести экземпляр"
                            : ex.getMessage()
            );
        }

        return "redirect:/my-operations";
    }

    /**
     * Форма комплектования ремонтного ЗНП.
     *
     * Открывается строкой ЗНП из меню «Моих операций»: вид заказа
     * определяет, что именно покажется — перечень ячеек или перечень
     * подсборок, и фильтр подставляется по нему (см.
     * {@link RepairKittingOperationService#findCandidateCells}).
     *
     * Фильтр тот же, что на странице «Изолятор брака» (разновидность,
     * классификатор, причина), и принимается теми же именами
     * параметров: человек, привыкший искать ячейку в изоляторе, должен
     * уметь сделать то же здесь, не разбираясь в новых параметрах.
     *
     * Граница — в сервисе: заказ обязан быть ремонтным и в работе, а
     * право проводить проверяется по привязке операции, потому что
     * /my-operations/** открыт всем аутентифицированным и адрес сам по
     * себе бюро от цеха не отличает.
     */
    @GetMapping("/repair-kitting/{znpId}")
    public String repairKitting(
            @PathVariable Long znpId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long classifierId,
            @RequestParam(required = false) Long reasonId,
            Authentication authentication,
            Model model
    ) {
        AppUser user = currentUser(authentication);
        QuarantineFilter filter = new QuarantineFilter(
                parseKind(kind), classifierId, reasonId
        );

        model.addAttribute("znp", repairKittingService.findOrderView(znpId));
        model.addAttribute(
                "cells",
                repairKittingService.findCandidateCells(znpId, filter)
        );
        model.addAttribute("items", repairKittingService.findItems(znpId));
        model.addAttribute(
                "classifierOptions",
                repairKittingService.findClassifierOptions()
        );
        model.addAttribute(
                "reasonGroups",
                repairKittingService.findReasonGroups()
        );
        model.addAttribute("kinds", QuarantineCellKind.values());
        model.addAttribute("filterKind", filter.kind());
        model.addAttribute("filterClassifierId", filter.classifierId());
        model.addAttribute("filterReasonId", filter.reasonId());
        model.addAttribute("searchApplied", filter.isFiltered());
        model.addAttribute("znpId", znpId);

        return decorate(user, "my-operations/repair-kitting", model);
    }

    /**
     * Проведение отмеченных ячеек по комплектовочной операции заказа.
     *
     * Форма присылает полный номер ячейки, а не её идентификатор:
     * изолятор оперирует номерами (у части из них экземпляра в реестре
     * нет), и номер — единственное, что одинаково описывает ячейку в
     * изоляторе, в составе заказа и в журнале перемещений.
     *
     * Успех и отказ возвращаются на ту же страницу сообщением: человек
     * должен увидеть обновлённый состав и остаток «Готово X из Y», а не
     * уходить в пустой список.
     */
    @PostMapping("/repair-kitting/{znpId}/kit")
    public String kit(
            @PathVariable Long znpId,
            @RequestParam(name = "cell", required = false) List<String> cells,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long classifierId,
            @RequestParam(required = false) Long reasonId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = currentUser(authentication);

        try {
            RepairKittingOperationService.KittingResult result =
                    repairKittingService.kit(znpId, cells, user);

            redirectAttributes.addFlashAttribute("successMessage", result.message());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage() == null
                            ? "Не удалось провести комплектование"
                            : ex.getMessage()
            );
        }

        return "redirect:" + repairKittingUrl(znpId, new QuarantineFilter(
                parseKind(kind), classifierId, reasonId
        ));
    }

    /**
     * Адрес формы с сохранённым отбором.
     *
     * После проведения фильтр обязан остаться заданным: иначе
     * исполнитель, искавший ячейки по причине брака, потерял бы отбор
     * ровно в тот момент, когда хочет провести следующие.
     */
    private static String repairKittingUrl(Long znpId, QuarantineFilter filter) {

        StringBuilder url = new StringBuilder("/my-operations/repair-kitting/")
                .append(znpId);
        char glue = '?';

        if (filter.kind() != null) {
            url.append(glue).append("kind=").append(filter.kind().name());
            glue = '&';
        }

        if (filter.classifierId() != null) {
            url.append(glue).append("classifierId=").append(filter.classifierId());
            glue = '&';
        }

        if (filter.reasonId() != null) {
            url.append(glue).append("reasonId=").append(filter.reasonId());
        }

        return url.toString();
    }

    /** Разновидность из параметра; пустое или неизвестное значение — «все». */
    private static QuarantineCellKind parseKind(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return QuarantineCellKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Режим из параметра; пустое или неизвестное значение — обычная половина. */
    private static String safeMode(String mode) {
        return mode == null ? "" : mode.trim();
    }

    /**
     * Фамилия и инициалы: подписывает и руководителя, и исполнителя
     * одинаково — тот же формат, что в списках подразделения.
     */
    private static String displayName(AppUser user) {
        String name = WorkshopStaffService.displayName(user);

        return name == null || name.isBlank() ? "Пользователь" : name;
    }

    /*
     * Подразделение и роль нужны для границы списка, поэтому связи
     * учётной записи загружаются вместе с ней.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись пользователя не найдена"
                ));
    }
}