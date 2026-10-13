package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.CustomerForm;
import ru.company.production.dto.CustomerOrderForm;
import ru.company.production.dto.ProductionOrderForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.CustomerOrderStatus;
import ru.company.production.entity.CustomerOrderType;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.TechProcessType;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ClassifierRouteOptionView;
import ru.company.production.repository.CustomerOrderItemRepository;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductTypeQuantityProjection;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.CustomerOrderService;
import ru.company.production.service.CustomerService;
import ru.company.production.service.ProductionOrderService;
import ru.company.production.service.SerialKittingService;
import ru.company.production.service.OrderSchedulingService;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;
import ru.company.production.service.checklist.ChecklistParser;

import java.beans.PropertyEditorSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Диспетчерский отдел.
 *
 * Заказы покупателя опираются на таблицы
 * customers / customer_orders / customer_order_items.
 *
 * Заказы на производство опираются на production_orders,
 * production_order_items и реестр серийников product_serials
 * (миграции V16, V17): состав выпуска формируется из позиций
 * выбранного заказа покупателя, а не вводится вручную.
 */
@Controller
@RequestMapping("/dispatcher")
@RequiredArgsConstructor
public class DispatcherController {

    private final CustomerOrderRepository customerOrderRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final ProductSerialRepository productSerialRepository;
    private final CustomerOrderService customerOrderService;
    private final CustomerService customerService;
    private final UserRepository userRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderService productionOrderService;
    private final OrderSchedulingService orderSchedulingService;
    private final ChecklistParser checklistParser;

    private static final String WORKSHOP_SCHEDULE_MODEL = "dispatcher/workshop-schedule";
    /*
     * Комплектование: список датчиков для заказа-комплектующего.
     * Номера выпускает заказ верхнего уровня, здесь они только
     * выбираются, поэтому контроллеру нужен доступ к составу.
     */
    private final SerialKittingService serialKittingService;

    /**
     * Порядок типов продукции на обзоре отдела.
     */
    private static final ProductType[] TYPE_ORDER = {
            ProductType.SENSOR,
            ProductType.SYSTEM,
            ProductType.CELL,
            ProductType.DEVICE
    };

    /**
     * Обзор отдела — главная страница диспетчера.
     */
    @GetMapping({"/general", "/overview"})
    public String general(Model model) {

        model.addAttribute("activePage", "general");

        model.addAttribute(
                "customerOrderCount",
                customerOrderRepository.countByActiveTrue()
        );

        /*
         * Заказы на производство считаются по своей таблице (V16):
         * карточка обзора показывает количество действующих выпусков.
         *
         * Значение берёт из модели DispatcherViewAdvice: там счётчик
         * считается по видам раздела (ZnpKind.productionOrderKinds()),
         * а не по всей таблице, и общий countByActiveTrue показал бы
         * вместе с выпусками ещё и групповые заказы.
         */
        BigDecimal plannedQuantity =
                customerOrderItemRepository.sumActivePlannedQuantity();

        model.addAttribute(
                "plannedQuantity",
                plannedQuantity == null
                        ? BigDecimal.ZERO
                        : plannedQuantity
        );

        model.addAttribute(
                "formattedPlannedQuantity",
                formatQuantity(plannedQuantity)
        );

        model.addAttribute(
                "typeCards",
                buildTypeCards()
        );

        model.addAttribute(
                "customerOrderNewCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.NEW
                )
        );

        model.addAttribute(
                "customerOrderInWorkCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.IN_WORK
                )
        );

        model.addAttribute(
                "customerOrderDoneCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.DONE
                )
        );

        model.addAttribute(
                "newStatusName",
                CustomerOrderStatus.NEW.getDisplayName()
        );

        model.addAttribute(
                "inWorkStatusName",
                CustomerOrderStatus.IN_WORK.getDisplayName()
        );

        model.addAttribute(
                "doneStatusName",
                CustomerOrderStatus.DONE.getDisplayName()
        );

        return "dispatcher/general";
    }

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/dispatcher/general";
    }

    /**
     * Заказы покупателя.
     */
    @GetMapping(
            value = {
                    "/customer-order",
                    "/customer-orders"
            }
    )
    public String customerOrder(Model model) {

        model.addAttribute("activePage", "customerOrder");

        model.addAttribute(
                "customerOrders",
                customerOrderRepository.findAllListItems()
        );

        model.addAttribute(
                "customerOrderCount",
                customerOrderRepository.countByActiveTrue()
        );

        /*
         * Карточки сводки по статусам считаются по активным заказам.
         */
        model.addAttribute(
                "customerOrderNewCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.NEW
                )
        );

        model.addAttribute(
                "customerOrderInWorkCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.IN_WORK
                )
        );

        model.addAttribute(
                "customerOrderDoneCount",
                customerOrderRepository.countActiveByStatus(
                        CustomerOrderStatus.DONE
                )
        );

        return "dispatcher/order/customer";
    }

    /**
     * Форма создания заказа покупателя.
     * Номер выдаётся автоматически (ЗП-ГГГГ-NNN),
     * дата заказа — текущая, срок — конец текущего года.
     */
    @GetMapping("/customer-order/create")
    public String createPage(Model model, HttpSession session) {

        if (!model.containsAttribute("customerOrderForm")) {
            Object preview = session.getAttribute("checklistPreview");
            if (preview instanceof ChecklistParser.ParsedChecklist parsed) {
                model.addAttribute("customerOrderForm", importOrderForm(parsed));
                model.addAttribute("importedFrom", parsed.fileName());
                session.removeAttribute("checklistPreview");
            } else {
                model.addAttribute("customerOrderForm", newOrderForm());
            }
        }

        fillFormModel(model);

        return "dispatcher/order/create";
    }

    /**
     * Загрузка чек-листа Б24: файл разбирается в предпросмотр и
     * кладётся в сессию, далее открывается форма создания с уже
     * заполненными позициями. Сохранение по-прежнему идёт через
     * обычный POST create — вся проверка изделия/техпроцесса в сервисе.
     */
    @PostMapping("/customer-order/import")
    public String importChecklist(
            @RequestParam("file") MultipartFile file,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", "Выберите файл чек-листа (.xls или .xlsx)");
            return "redirect:/dispatcher/customer-order/create";
        }

        try {
            session.setAttribute("checklistPreview", checklistParser.parse(file));
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/dispatcher/customer-order/create";
        }

        return "redirect:/dispatcher/customer-order/create";
    }

    /**
     * Сохранение нового заказа покупателя.
     */
    @PostMapping("/customer-order/create")
    public String create(
            @Valid
            @ModelAttribute("customerOrderForm")
            CustomerOrderForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        if (form.getNumber() == null || form.getNumber().isBlank()) {
            form.setNumber(customerOrderService.nextNumber());
        }

        AppUser author = authentication == null
                ? null
                : userRepository.findByUsernameIgnoreCase(
                        authentication.getName()
                ).orElse(null);

        if (!errors.hasErrors()) {
            try {
                customerOrderService.create(form, author);
            } catch (
                    EntityNotFoundException
                    | IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject(
                        "customerorder.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillFormModel(model);

            return "dispatcher/order/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ покупателя «" + form.getNumber().trim()
                        + "» успешно создан"
        );

        return "redirect:/dispatcher/customer-order";
    }

    /**
     * Просмотр конкретного заказа покупателя
     * со всем его составом изделий.
     */
    @GetMapping("/customer-order/{id}")
    public String customerOrderView(
            @PathVariable Long id,
            Model model
    ) {
        model.addAttribute("activePage", "customerOrder");

        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElse(null);

        if (order == null) {
            model.addAttribute(
                    "errorMessage",
                    "Заказ покупателя не найден"
            );

            return "dispatcher/order/view";
        }

        model.addAttribute("order", order);

        model.addAttribute(
                "items",
                customerOrderItemRepository.findItemsByOrderId(id)
        );

        /*
         * Серийники под каждой позицией состава: плоский перечень
         * резерва выше по странице не отвечает, какой номер к какой
         * строке заказа относится.
         */
        model.addAttribute(
                "serialsByItem",
                serialsByItemPosition(id)
        );

        return "dispatcher/order/view";
    }

    /**
     * Форма редактирования заказа покупателя.
     *
     * Переиспользует шаблон создания: тот же каскад выбора изделий
     * и состав системы. Отличие — заполненные значения и режим
     * правки (атрибут editing), под который шаблон меняет заголовок,
     * адрес формы и подпись кнопки.
     */
    @GetMapping("/customer-order/{id}/edit")
    public String editPage(
            @PathVariable Long id,
            Model model
    ) {
        CustomerOrder order = customerOrderRepository
                .findFullById(id)
                .orElse(null);

        if (order == null) {
            return "redirect:/dispatcher/customer-order";
        }

        model.addAttribute(
                "customerOrderForm",
                editOrderForm(order)
        );
        model.addAttribute("editing", true);

        fillFormModel(model);

        return "dispatcher/order/create";
    }

    /**
     * Сохранение изменений заказа покупателя.
     */
    @PostMapping("/customer-order/{id}/edit")
    public String edit(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("customerOrderForm")
            CustomerOrderForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        form.setId(id);

        if (!errors.hasErrors()) {
            try {
                customerOrderService.update(id, form);
            } catch (
                    EntityNotFoundException
                    | IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject(
                        "customerorder.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            model.addAttribute("editing", true);

            fillFormModel(model);

            return "dispatcher/order/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ покупателя «" + form.getNumber().trim()
                        + "» успешно изменён"
        );

        return "redirect:/dispatcher/customer-order/" + id;
    }
    /**
     * Предпросмотр календарного плана заказа покупателя.
     *
     * Расчёт без записи в базу: диспетчер видит даты и подразделения
     * операций и только потом решает, вносить ли план. Ошибка расчёта
     * (нет техпроцесса, не задан приоритет) возвращает на карточку.
     */
    @GetMapping("/customer-order/{id}/schedule-preview")
    public String schedulePreview(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "customerOrder");

        try {
            model.addAttribute(
                    "schedule",
                    orderSchedulingService.preview(id)
            );
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        model.addAttribute("orderId", id);

        return "dispatcher/order/schedule-preview";
    }

    /**
     * Подтверждение плана: свежий расчёт записывается в план заказа,
     * прежние строки заменяются целиком (см. OrderSchedulingService).
     */
    @PostMapping("/customer-order/{id}/schedule-confirm")
    public String scheduleConfirm(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            orderSchedulingService.confirm(id);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Календарный план внесён в заказ"
        );

        return "redirect:/dispatcher/customer-order/" + id;
    }

    /**
     * Планирование заказа покупателя — перевод в работу.
     */
    @PostMapping("/customer-order/{id}/plan")
    public String plan(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            customerOrderService.plan(id);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
            ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ запланирован и взят в работу"
        );

        return "redirect:/dispatcher/customer-order/" + id;
    }

    /**
     * Заказчики — справочник контрагентов.
     */
    @GetMapping(
            value = {
                    "/customer",
                    "/customers"
            }
    )
    public String customer(
            @ModelAttribute("customerForm")
            CustomerForm customerForm,
            Model model
    ) {
        model.addAttribute("activePage", "customer");

        model.addAttribute(
                "customers",
                customerService.findAll()
        );

        return "dispatcher/customer/index";
    }

    /**
     * Занесение нового заказчика.
     *
     * Дополнительных реквизитов пока нет: достаточно наименования,
     * код (ЗК-NNN) генерируется автоматически.
     */
    @PostMapping("/customer/create")
    public String createCustomer(
            @Valid
            @ModelAttribute("customerForm")
            CustomerForm customerForm,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!errors.hasErrors()) {
            try {
                customerService.create(customerForm);
            } catch (
                    IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject("customer.save", exception.getMessage());
            }
        }

        if (errors.hasErrors()) {
            model.addAttribute("activePage", "customer");

            model.addAttribute(
                    "customers",
                    customerService.findAll()
            );

            return "dispatcher/customer/index";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказчик «" + customerForm.getName().trim()
                        + "» успешно добавлен"
        );

        return "redirect:/dispatcher/customer";
    }

    /**
     * Заказы на производство.
     *
     * Готово по каждой строке считается как число позиций в статусе
     * «Выполнена» — отсюда параметр DONE в запросе-проекции.
     */
    @GetMapping(
            value = {
                    "/production-order",
                    "/production-orders"
            }
    )
    public String productionOrder(Model model) {

        model.addAttribute("activePage", "productionOrder");

        model.addAttribute(
                "productionOrders",
                productionOrderRepository.findListItemsByKinds(
                        ProductionItemStatus.DONE,
                        ZnpKind.productionOrderKinds()
                )
        );

        /*
         * Счётчик «Всего заказов» и счётчик пункта меню берёт из модели
         * DispatcherViewAdvice: там он считается по видам этого раздела
         * (ZnpKind.productionOrderKinds()), а общий countByActiveTrue
         * показал бы вместе с выпусками ещё и групповые заказы.
         */
        model.addAttribute(
                "productionOrderNewCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.NEW,
                        ZnpKind.productionOrderKinds()
                )
        );

        model.addAttribute(
                "productionOrderInWorkCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.IN_WORK,
                        ZnpKind.productionOrderKinds()
                )
        );

        model.addAttribute(
                "productionOrderDoneCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.DONE,
                        ZnpKind.productionOrderKinds()
                )
        );

        /*
         * Виды ЗНП нужны меню кнопки создания: пункт меню и есть выбор
         * вида, поэтому список отдаётся на страницу списка, а не в
         * форму заказа (селектор вида из формы убран).
         *
         * В этом разделе остаётся только комплектование: групповые и
         * ремонтные виды перенесены в отдельный пункт меню
         * «Групповые ЗНП» (см. groupZnp()), поэтому кнопкам создания
         * здесь отдаётся ZnpKind.productionOrderKinds(), а не все виды —
         * иначе в шапке висела бы кнопка заказа, который тут больше не
         * появляется.
         */
        model.addAttribute(
                "znpKinds",
                ZnpKind.productionOrderKinds()
        );

        return "dispatcher/order/production";
    }

    /**
     * Групповые ЗНП — самостоятельные заказы (миграции V37, V38).
     *
     * Раздел существует потому, что у этих заказов иной смысл, чем у
     * выпуска: они заявлены изделием и количеством, ни в чей выпуск не
     * вписываются и родителя не имеют. Держать их в одном списке с
     * выпусками значило бы показывать в нём строки без заказа покупателя,
     * без состава и без серийников — цех читал бы их как незаполненные
     * заказы.
     *
     * Отбор идёт по набору видов (ZnpKind.groupKinds()), а не по признаку
     * «родителя нет»: последний отличал бы групповой заказ от выпуска
     * только случайно, а новый вид в перечне пришлось бы дописывать
     * вручную.
     */
    @GetMapping("/group-znp")
    public String groupZnp(Model model) {

        model.addAttribute("activePage", "groupZnp");

        model.addAttribute(
                "groupOrders",
                productionOrderRepository.findListItemsByKinds(
                        ProductionItemStatus.DONE,
                        ZnpKind.groupKinds()
                )
        );

        /*
         * Счётчик пункта меню кладёт DispatcherViewAdvice
         * (groupZnpCount); здесь считаются только карточки сводки —
         * по статусам в границах видов этого раздела.
         */
        model.addAttribute(
                "groupZnpNewCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.NEW,
                        ZnpKind.groupKinds()
                )
        );

        model.addAttribute(
                "groupZnpInWorkCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.IN_WORK,
                        ZnpKind.groupKinds()
                )
        );

        model.addAttribute(
                "groupZnpDoneCount",
                productionOrderRepository.countActiveByStatusAndKinds(
                        ProductionOrderStatus.DONE,
                        ZnpKind.groupKinds()
                )
        );

        /*
         * Кнопки создания — по кнопке на каждый вид раздела. Список
         * берётся из того же признака, что и отбор строк, поэтому
         * добавленный в ZnpKind вид появится и в списке, и среди кнопок
         * одной правкой в enum.
         */
        model.addAttribute("znpKinds", ZnpKind.groupKinds());

        return "dispatcher/order/group-znp";
    }

    /**
     * Форма создания заказа на производство.
     *
     * Состав выпуска в форме не редактируется: он выведется из позиций
     * выбранного заказа покупателя при сохранении, и каждая позиция
     * получит серийный номер.
     *
     * Вид ЗНП приходит параметром {@code kind} — его задаёт пункт меню
     * кнопки создания, а не выбор в самой форме: набор полей зависит от
     * вида, и показывать диспетчеру поля чужого вида незачем.
     */
    @GetMapping("/production-order/create")
    public String createProductionPage(
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false, defaultValue = "false")
            boolean release,
            @RequestParam(required = false) String kind,
            Model model
    ) {

        /*
         * Что за заказ заводит диспетчер, определяет кнопка, по которой
         * открыта форма, а не выбор в самой странице: выпуск (ЗП) создают
         * из заказа покупателя, заказ-комплектующий (ЗНП) — из этого
         * окна. Переключатель «ЗП / ЗНП» из интерфейса убран: он лишь
         * добавлял клик перед работой.
         */
        model.addAttribute("releaseOrder", release);

        /*
         * Вид ЗНП разбирается тем же способом, что и в сервисе:
         * неизвестное или пустое значение читается как комплектование,
         * поэтому ссылка со старым адресом (без вида) продолжает
         * открывать прежнюю форму.
         */
        ZnpKind znpKind = ZnpKind.ofNullable(kind);

        model.addAttribute("znpKind", znpKind);

        if (!model.containsAttribute("productionOrderForm")) {
            ProductionOrderForm form = newProductionOrderForm();

            /*
             * Родитель подставляется, когда форму открыли из карточки
             * выпуска кнопкой «Добавить комплектующий».
             *
             * Чужой или снятый из работы заказ в расчёт не берётся —
             * сервис отклонил бы такую привязку, а форма с заведомо
             * неверным родителем только запутала бы.
             */
            if (parentId != null) {
                productionOrderRepository
                        .findDetailedById(parentId)
                        .filter(ProductionOrder::isActive)
                        .filter(ProductionOrder::isRoot)
                        .ifPresent(parent -> {
                            form.setParentProductionOrderId(
                                    parent.getId()
                            );

                            /*
                             * Автоподобранный номер убирается: он
                             * относится к выпуску, а комплектующему
                             * нужен собственный — из внешнего учёта.
                             * Оставленный номер диспетчер принял бы за
                             * штатный и сохранил, не заметив подмены.
                             */
                            form.setNumber(null);
                        });
            }

            form.setOrderLevel(
                    release
                            ? ProductionOrderForm.LEVEL_ROOT
                            : ProductionOrderForm.LEVEL_COMPONENT
            );

            /*
             * Вид записывается разобранным именем, а не строкой из
             * запроса: в форму и дальше попадает каноническое значение,
             * и селектор (при правке — скрытое поле) открыт на том же
             * виде, который реально сохранится.
             */
            form.setZnpKind(znpKind.name());

            /*
             * Автономер ЗНП-ГГГГ-NNN относится к выпуску. Комплектующему
             * номер нужен собственный — из внешнего учёта, поэтому в
             * форме ЗНП поле остаётся пустым, а не заполненным
             * неподходящим номером.
             */
            if (!release) {
                form.setNumber(null);
            }

            model.addAttribute("productionOrderForm", form);
        }

        fillProductionFormModel(model);

        return "dispatcher/order/production-create";
    }

    /**
     * Сохранение заказа на производство.
     */
    @PostMapping("/production-order/create")
    public String createProduction(
            @RequestParam(required = false, defaultValue = "false")
            boolean release,
            @Valid
            @ModelAttribute("productionOrderForm")
            ProductionOrderForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        /*
         * Тип заказа возвращается в модель: при ошибке валидации форма
         * отрисовывается заново, и без признака она показалась бы
         * выпуском, хотя диспетчер заводил комплектующий (и наоборот).
         */
        model.addAttribute("releaseOrder", release);

        /*
         * Тип заказа, выбранный переключателем, сверяется с родителем
         * до обращения к сервису.
         *
         * Уровень в базе определяется родителем, переключатель лишь
         * показывает нужные поля — поэтому расхождение двух признаков
         * привело бы к заказу не того уровня: «ЗНП» без родителя
         * создал бы выпуск, а «ЗП» со значением родителя, оставшимся в
         * скрытом поле (например, после возврата из карточки выпуска),
         * создал бы комплектующего.
         */
        if (form.isComponentLevel()
                && form.getResolvedKind().isSerialBased()
                && form.getParentProductionOrderId() == null) {

            errors.rejectValue(
                    "parentProductionOrderId",
                    "productionorder.parent.required",
                    "Выберите заказ верхнего уровня, внутрь которого "
                            + "входит заказ на комплектование"
            );
        }

        /*
         * Самостоятельному заказу (выпуск, групповой или ремонтный ЗНП)
         * родитель не полагается: значение могло остаться в скрытом
         * поле после возврата из карточки выпуска.
         */
        /*
         * Заказ покупателя при этом остаётся как введён: у самостоятельного
         * заказа (выпуск, групповой или ремонтный ЗНП) его наследовать не
         * от чего, и он выбирается в форме. Обнуление поля отняло бы
         * заказчика у корректной заявки.
         */
        if (!form.isComponentLevel()
                || !form.getResolvedKind().isSerialBased()) {

            form.setParentProductionOrderId(null);
        }

        /*
         * Автоподбор номера работает только для заказа верхнего уровня.
         * Заказу-комплектующему номер обязателен ручной ввод: он
         * приходит из внешнего учёта, и подставленный по шаблону номер
         * не соответствовал бы документу, а сервис уже не смог бы
         * отличить пропуск поля от намеренного значения.
         */
        if (!form.isComponentLevel()
                && (form.getNumber() == null
                || form.getNumber().isBlank())) {
            form.setNumber(productionOrderService.nextNumber());
        }

        AppUser author = authentication == null
                ? null
                : userRepository.findByUsernameIgnoreCase(
                        authentication.getName()
                ).orElse(null);

        /*
         * Номер извлекается до валидации и null-безопасно: у заказа-
         * комплектующего автоподбор сознательно не выполняется, поэтому
         * пустое поле здесь — штатная ситуация, а не исключение.
         */
        String number = form.getNumber() == null
                ? ""
                : form.getNumber().trim();

        /*
         * Сообщение различается по режиму формы, а не по родителю:
         * серийники выдаёт только выпуск, а групповой и ремонтный ЗНП
         * (V37) существуют без родителя — по признаку «родителя нет»
         * они получили бы текст о выпущенных серийных номерах.
         */
        boolean component = form.isComponentLevel();

        if (!errors.hasErrors()) {
            try {
                productionOrderService.create(form, author);
            } catch (
                    EntityNotFoundException
                    | IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject(
                        "productionorder.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillProductionFormModel(model);

            return "dispatcher/order/production-create";
        }

        /*
         * Сообщение различается: серийные номера выдаёт только выпуск
         * верхнего уровня, заказу-комплектующему они не назначаются —
         * он входит внутрь уже выпущенного датчика.
         */
        redirectAttributes.addFlashAttribute(
                "successMessage",
                component
                        ? "Заказ на производство (комплектующий) «" + number
                        + "» создан"
                        : "Заказ на производство «" + number
                        + "» создан, серийные номера выпущены"
        );

        /*
         * Возврат — в тот раздел, где заказ и живёт: групповой заказ,
         * созданный из своего пункта меню, попал бы обратно в «Заказы на
         * производство», где его не видно, и диспетчер счёл бы создание
         * неудачным.
         *
         * Раздел берётся из разобранного вида формы, а не из признака
         * «родителя нет»: групповой и ремонтный ЗНП тоже без родителя, но
         * выпуск из них не получается (см. ZnpKind#isGroupKind).
         */
        return "redirect:/dispatcher"
                + (form.getResolvedKind().isGroupKind()
                ? "/group-znp"
                : "/production-order");
    }

    /**
     * Просмотр заказа на производство: шапка и состав выпуска
     * с серийными номерами изделий.
     */
    @GetMapping("/production-order/{id}")
    public String productionOrderView(
            @PathVariable Long id,
            Model model
    ) {
        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElse(null);

        /*
         * Раздел подсветки меню определяется самим заказом, а не адресом:
         * карточка одна для обоих разделов, и групповой заказ, открытый
         * по прямой ссылке, обязан подсветить «Групповые ЗНП», а не
         * «Заказы на производство».
         *
         * Признак ставится до проверки на отсутствие заказа: страница
         * с ошибкой тоже отрисовывает меню.
         */
        model.addAttribute(
                "activePage",
                order != null && order.isGroupZnp()
                        ? "groupZnp"
                        : "productionOrder"
        );

        /*
         * Счётчик пункта меню кладёт DispatcherViewAdvice — здесь его
         * переопределять нечем: значение по всей таблице показало бы в
         * меню выпуски вместе с групповыми заказами.
         */
        if (order == null) {
            model.addAttribute(
                    "errorMessage",
                    "Заказ на производство не найден"
            );

            return "dispatcher/order/production-view";
        }

        model.addAttribute("order", order);

        /*
         * Причина, по которой заказ нельзя взять в работу (null — можно).
         * Карточка раньше не предлагала запуск вовсе: диспетчер видел
         * «Отменить» и «Удалить» и обязан был идти за «В работу» в список.
         * Проверка повторяет ProductionOrderService#plan и
         * ProductionOrderListItemView#getPlanBlockReason — тот же признак
         * (позиции без номера ЗНП), поэтому карточка и список не спорят о
         * том, разрешён ли запуск.
         */
        model.addAttribute("planBlockReason", planBlockReason(order));

        model.addAttribute(
                "items",
                productionOrderItemRepository.findItemsByOrderId(id)
        );

        model.addAttribute(
                "doneItemCount",
                productionOrderItemRepository.countByOrderIdAndStatus(
                        id,
                        ProductionItemStatus.DONE
                )
        );

        model.addAttribute(
                "itemCount",
                productionOrderItemRepository.countByOrderId(id)
        );

        /*
         * Входящие заказы-комплектующие (ЗНП). Считаются через
         * findActiveChildren, а не через коллекцию children сущности:
         * обращение к ней из шаблона вне сессии дало бы
         * LazyInitializationException, а fetch-join в заголовке
         * размножил бы строки результата.
         *
         * Выпуск и заказ-комплектующий: у обоих блок «Заказы-
         * комплектующие» создаёт ЗНП по операциям комплектования
         * датчика (для ЗНП — его собственного датчика, без выбора),
         * поэтому список созданных детей нужен и там и там. У
         * группового и ремонтного заказа детей не бывает.
         */
        model.addAttribute(
                "children",
                order.isRoot() || order.isSerialBasedKind()
                        ? productionOrderRepository
                        .findActiveChildrenDetailed(id)
                        : List.<ProductionOrder>of()
        );

        /*
         * Даты выпуска читаются шаблоном напрямую, поэтому заказ
         * берётся детальным запросом (parent), а позиции — своим.
         *
         * Для заказ-комплектующего показывается обратная связь: какие
         * датчики он комплектует. Она нужна в карточке так же, как для
         * выпуска список комплектующих, — иначе ЗНП нечем было бы
         * открыть, кроме как через сам датчик.
         */
        model.addAttribute(
                "kitting",
                order.isRoot()
                        ? List.of()
                        : serialKittingService.findKitting(id)
        );

        /*
         * Датчики для селекта блока «Заказы-комплектующие»: блок есть
         * только в карточке выпуска, поэтому для ЗНП список пуст —
         * раньше сюда уходил отдельный запрос findAttachedSerials,
         * результат которого шаблон не печатал.
         */
        model.addAttribute(
                "releaseSerials",
                order.isRoot()
                        ? serialKittingService.findAvailableSerials(id)
                        : List.of()
        );

        /*
         * Датчик(и) самого заказ-комплектующего: форма создания
         * заказов-комплектующих в карточке ЗНП работает без выбора
         * серийника — операции берутся из базового техпроцесса
         * изделия того номера, к которому заказ уже привязан.
         */
        model.addAttribute(
                "orderSerials",
                order.isRoot()
                        ? List.of()
                        : serialKittingService.findAttachedSerials(id)
        );

        /*
         * Виды ЗНП нужны кнопкам блока «Заказы-комплектующие»: каждая
         * кнопка ведёт в форму сразу со своим видом (параметр kind) и с
         * родителем этого выпуска. Без атрибута шаблон отрисовал бы
         * пустой блок, и добавить комплектующего из карточки было бы
         * нечем.
         *
         * Отдаётся набор раздела, а не все виды: кнопка в карточке
         * выпуска создаёт заказ внутри выпуска (parentId), а групповые
         * и ремонтные виды самостоятельны и родителя не принимают —
         * такая кнопка открыла бы форму, которую сервис отклонил бы.
         */
        model.addAttribute(
                "znpKinds",
                ZnpKind.productionOrderKinds()
        );

        return "dispatcher/order/production-view";
    }

    /**
     * Взятие заказа на производство в работу.
     */
    @PostMapping("/production-order/{id}/plan")
    public String planProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return runProductionAction(
                () -> productionOrderService.plan(id),
                id,
                "Заказ взят в работу",
                redirectAttributes
        );
    }

    /**
     * Выполнение заказа на производство.
     */
    @PostMapping("/production-order/{id}/complete")
    public String completeProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return runProductionAction(
                () -> productionOrderService.complete(id),
                id,
                "Заказ выполнен, все позиции закрыты",
                redirectAttributes
        );
    }

    /**
     * Отмена заказа на производство.
     */
    @PostMapping("/production-order/{id}/cancel")
    public String cancelProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return runProductionAction(
                () -> productionOrderService.cancel(id),
                id,
                "Заказ на производство отменён",
                redirectAttributes
        );
    }

    /**
     * Возврат отменённого ЗНП в работу: заказ переходит в «Новый».
     */
    @PostMapping("/production-order/{id}/reopen")
    public String reopenProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        return runProductionAction(
                () -> productionOrderService.reopen(id),
                id,
                "Заказ-комплектующий возвращён в работу, статус — «Новый»",
                redirectAttributes
        );
    }

    /**
     * Удаление заказа на производство.
     */
    @PostMapping("/production-order/{id}/delete")
    public String deleteProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        /*
         * Раздел запоминается до удаления: после удаления заказ не
         * найти, а вернуть диспетчера надо в тот список, где заказ и
         * числился — групповые виды живут в «Групповых ЗНП».
         */
        String sectionListUrl = productionOrderRepository
                .findById(id)
                .map(ProductionOrder::getSectionListUrl)
                .orElse("/dispatcher/production-order");

        try {
            productionOrderService.delete(id);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/production-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ на производство удалён"
        );

        return "redirect:" + sectionListUrl;
    }
    /**
     * Форма создания из предпросмотра чек-листа: каждая строка листа
     * становится позицией. Тип — датчик (основной сценарий), полное
     * наименование подберёт классификатор при сохранении; если строка
     * не датчик, диспетчер поправит тип/изделие прямо в форме.
     */
    private CustomerOrderForm importOrderForm(
            ChecklistParser.ParsedChecklist parsed
    ) {
        CustomerOrderForm form = newOrderForm();
        form.getItems().clear();

        for (ChecklistParser.ParsedRow row : parsed.rows()) {
            CustomerOrderForm.CustomerOrderItemForm item =
                    new CustomerOrderForm.CustomerOrderItemForm();

            item.setProductType(ProductType.SENSOR);
            item.setFullName(row.name());
            item.setQuantity(row.qty());
            item.setUnit(toUnitName(row.unit()));
            item.setTechProcessType(TechProcessType.BASIC);
            item.setNote(row.note().isBlank() ? null : row.note());

            form.getItems().add(item);
        }

        return form;
    }

    /**
     * Единица измерения из листа в имя enum; при неизвестном значении —
     * «шт.» (значения селектора формы сверяются с MeasurementUnit.name()).
     */
    private String toUnitName(String raw) {
        if (raw == null) {
            return "PCS";
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("кг") || v.equals("kg")) return "KG";
        if (v.startsWith("г"))  return "G";
        if (v.startsWith("м") && !v.startsWith("мм") && !v.startsWith("мл")) return "M";
        if (v.startsWith("мм")) return "MM";
        if (v.startsWith("мл")) return "ML";
        if (v.startsWith("л"))  return "L";
        return "PCS";
    }
    /**
     * Переход после действия над заказом: ошибка возвращается
     * на страницу заказа, успех — тоже на неё, чтобы был виден
     * новый статус.
     */
    /**
     * Причина, по которой заказ нельзя взять в работу, либо {@code null}.
     *
     * Карточка показывает кнопку «В работу» и без этой причины, но
     * отключённая кнопка обязана объяснять отказ наведением — так же, как
     * это делает подсказка в списке (см.
     * ProductionOrderListItemView#getPlanBlockReason). Здесь тот же
     * признак: закрытый статус и позиции состава без номера ЗНП.
     *
     * Остальные проверки сервиса (вложенные комплектующие, план операций)
     * не дублируются: они требуют обхода дерева, а отказывает в них
     * сервис — баннером после возврата на карточку.
     */
    private String planBlockReason(ProductionOrder order) {

        ProductionOrderStatus status = order.getStatus();

        if (status == ProductionOrderStatus.DONE
                || status == ProductionOrderStatus.CANCELLED) {
            return "Заказ закрыт и не может быть взят в работу";
        }

        long missing = productionOrderItemRepository
                .countWithoutZnpByOrderId(order.getId());

        return missing > 0
                ? "Внесены не все ЗНП: осталось заполнить " + missing
                        + " — заполните номер напротив каждого серийника"
                : null;
    }

    private String runProductionAction(
            Runnable action,
            Long id,
            String successMessage,
            RedirectAttributes redirectAttributes
    ) {
        try {
            action.run();
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/production-order/" + id;
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                successMessage
        );

        return "redirect:/dispatcher/production-order/" + id;
    }

    /**
     * Разбор значений формы заказа:
     * количество принимает и точку, и запятую,
     * дата — формат дд.мм.гггг.
     */
    @InitBinder("customerOrderForm")
    public void bindOrderForm(WebDataBinder binder) {
        binder.registerCustomEditor(
                BigDecimal.class,
                new QuantityEditor()
        );

        binder.registerCustomEditor(
                LocalDate.class,
                new RuDateEditor()
        );
    }

    /**
     * Дата в заказе на производство — тот же формат дд.мм.гггг;
     * количества в этой форме нет.
     */
    @InitBinder("productionOrderForm")
    public void bindProductionOrderForm(WebDataBinder binder) {
        binder.registerCustomEditor(
                LocalDate.class,
                new RuDateEditor()
        );
    }

    /**
     * Значения формы по умолчанию: текущая дата заказа
     * и срок в пределах текущего года.
     */
    private CustomerOrderForm newOrderForm() {
        CustomerOrderForm form = new CustomerOrderForm();

        LocalDate today = LocalDate.now();

        form.setNumber(customerOrderService.nextNumber());
        form.setOrderDate(today);
        form.setDueDate(LocalDate.of(today.getYear(), 12, 31));
        form.setStatus(CustomerOrderStatus.NEW);
        form.setPriorityId(customerOrderService.findDefaultPriorityId());
        form.setOrderType(CustomerOrderType.PRODUCTION);

        CustomerOrderForm.CustomerOrderItemForm item =
                new CustomerOrderForm.CustomerOrderItemForm();

        item.setUnit("PCS");
        item.setQuantity(BigDecimal.ONE);
        item.setTechProcessType(TechProcessType.BASIC);

        form.getItems().add(item);

        return form;
    }

    /**
     * Форма правки: значения берутся из сохранённого заказа,
     * позиции — в порядке их нумерации.
     */
    private CustomerOrderForm editOrderForm(CustomerOrder order) {
        CustomerOrderForm form = new CustomerOrderForm();

        form.setId(order.getId());
        form.setVersion(order.getVersion());
        form.setNumber(order.getNumber());
        form.setCustomerId(
                order.getCustomer() == null
                        ? null
                        : order.getCustomer().getId()
        );
        form.setOrderDate(order.getOrderDate());
        form.setDueDate(order.getDueDate());
        form.setContractNumber(order.getContractNumber());
        form.setNote(order.getNote());
        form.setB24OrderUrl(order.getB24OrderUrl());
        form.setB24ChecklistUrl(order.getB24ChecklistUrl());
        form.setPriorityId(
                order.getPriority() == null
                        ? null
                        : order.getPriority().getId()
        );
        form.setStatus(order.getStatus());
        form.setOrderType(order.getOrderType());

        for (CustomerOrderItem item : order.getItems()) {
            CustomerOrderForm.CustomerOrderItemForm itemForm =
                    new CustomerOrderForm.CustomerOrderItemForm();

            itemForm.setProductClassifierId(
                    item.getProductClassifier() == null
                            ? null
                            : item.getProductClassifier().getId()
            );
            /*
             * Тип изделия нужен форме, чтобы решить, что показывать:
             * список изделий или поле ручного ввода полного наименования.
             */
            itemForm.setProductType(
                    item.getProductClassifier() == null
                            ? null
                            : item.getProductClassifier().getProductType()
            );
            itemForm.setFullName(item.getFullName());
            itemForm.setQuantity(item.getQuantity());
            itemForm.setUnit(
                    item.getUnit() == null ? null : item.getUnit().name()
            );
            itemForm.setTechProcessType(item.getTechProcessType());
            itemForm.setDueDate(item.getDueDate());
            itemForm.setNote(item.getNote());

            form.getItems().add(itemForm);
        }

        return form;
    }

    /**
     * Справочники для формы заказа.
     */
    private void fillFormModel(Model model) {
        model.addAttribute("activePage", "customerOrder");

        // См. fillProductionFormModel: флаг режима нужен всегда,
        // иначе форма создания падает на null → boolean в SpEL.
        if (!model.containsAttribute("editing")) {
            model.addAttribute("editing", false);
        }

        model.addAttribute(
                "customers",
                customerOrderService.findActiveCustomers()
        );

        model.addAttribute(
                "classifierOptions",
                customerOrderService.findClassifierOptions()
        );

        model.addAttribute(
                "units",
                customerOrderService.findUnits()
        );

        model.addAttribute(
                "statuses",
                customerOrderService.findStatuses()
        );

        model.addAttribute(
                "priorities",
                customerOrderService.findPriorities()
        );

        model.addAttribute(
                "orderTypes",
                customerOrderService.findOrderTypes()
        );

        model.addAttribute(
                "techProcessTypes",
                customerOrderService.findTechProcessTypes()
        );

        model.addAttribute(
                "productTypes",
                customerOrderService.findProductTypes()
        );

        model.addAttribute("currentYear", LocalDate.now().getYear());
    }

    /**
     * Значения формы выпуска по умолчанию: номер ЗНП-ГГГГ-NNN
     * и сегодняшняя дата открытия.
     *
     * Срок исполнения и статус сюда не кладутся: срок берётся из
     * заказа покупателя, а статус нового выпуска всегда «Новый» —
     * им управляет сервис и кнопки карточки заказа.
     */
    private ProductionOrderForm newProductionOrderForm() {
        ProductionOrderForm form = new ProductionOrderForm();

        form.setNumber(productionOrderService.nextNumber());
        form.setOrderDate(LocalDate.now());

        /*
         * Вид задаётся явно, хотя сервис прочитал бы пустое значение как
         * комплектование: селектор формы обязан открываться на том же
         * значении, которое реально будет сохранено, иначе диспетчер
         * увидел бы «выбрана первая строка», а сохранилось бы другое.
         */
        form.setZnpKind(ZnpKind.KITTING.name());

        return form;
    }

    /**
     * Справочники формы выпуска.
     *
     * Свободные заказы покупателя и комплектовочные операции берутся
     * из сервиса: занятые выпуском заказы в список не попадают,
     * поэтому диспетчер физически не сможет нарушить связь
     * «один заказ покупателя — один выпуск».
     */
    private void fillProductionFormModel(Model model) {
        fillProductionFormModel(model, null);
    }

    /**
     * @param editingOrder правый заказ при изменении формы — нужен,
     *                     чтобы вернуть в выборку его заказчика
     */
    private void fillProductionFormModel(Model model,
                                        ProductionOrder editingOrder) {
        /*
         * Подсветка меню определяется видом заказа, а не адресом:
         * форма общая для обоих разделов, и групповой заказ, открытый
         * кнопкой из «Групповых ЗНП», обязан подсвечивать именно их —
         * в том числе при повторной отрисовке после ошибки валидации,
         * когда адрес остаётся прежним, а вид уже выбран.
         */
        model.addAttribute(
                "activePage",
                formMenuSection(model, editingOrder)
        );

        /*
         * Режим формы: создание или правка. SpEL не превращает null в
         * boolean, поэтому флаг задаётся всегда — иначе тернарные
         * выражения в шаблоне падают на EL1001E при открытии формы
         * создания. При правке вызывающий метод уже положил true,
         * поэтому существующее значение сохраняется.
         */
        if (!model.containsAttribute("editing")) {
            model.addAttribute("editing", false);
        }

        /*
         * При правке заказчик выпуска занят этим же заказом и в
         * свободные не попадает, поэтому возвращается в выборку
         * отдельно: без него select отрисовался бы пустым, а сохранение
         * после ошибки валидации потеряло бы привязку.
         */
        List<CustomerOrder> customerOrders =
                new ArrayList<>(
                        productionOrderService.findFreeCustomerOrders()
                );

        if (editingOrder != null
                && editingOrder.getCustomerOrder() != null
                && editingOrder.isActive()) {

            CustomerOrder bound = editingOrder.getCustomerOrder();

            boolean present = customerOrders.stream()
                    .anyMatch(option -> option.getId().equals(bound.getId()));

            if (!present) {
                customerOrders.add(bound);
                customerOrders.sort(
                        Comparator.comparing(CustomerOrder::getNumber)
                );
            }
        }

        model.addAttribute("freeCustomerOrders", customerOrders);

        /*
         * Номера датчиков, доступные заказу-комплектующему: их
         * выпускает родительский заказ (ЗП), поэтому выбор строится по
         * родителю. Источник родителя выбирается по ситуации:
         *
         *   правка — родитель редактируемого заказа;
         *   создание после ошибки валидации — выбор из самой формы,
         *     иначе список остался бы пустым и отмеченные пользователем
         *     номера пропали бы вместе с ошибкой.
         */
        Long serialsParentId = null;

        if (editingOrder != null
                && editingOrder.getParentProductionOrder() != null) {

            serialsParentId =
                    editingOrder.getParentProductionOrder().getId();

        } else if (editingOrder == null) {

            Object boundForm =
                    model.asMap().get("productionOrderForm");

            if (boundForm instanceof ProductionOrderForm form) {
                serialsParentId = form.getParentProductionOrderId();
            }
        }

        model.addAttribute(
                "parentSerials",
                serialsParentId == null
                        ? List.of()
                        : serialKittingService
                                .findAvailableSerials(serialsParentId)
        );

        /*
         * Вид ЗНП (миграция V37): комплектование, групповой на ячейки,
         * групповой на приборы, ремонт ячеек, ремонт подсборок.
         *
         * Список отдаётся весь — выбор вида есть только у заказав
         * комплектующего, и в шаблоне селектор закрыт при правке так же,
         * как закрыт выбор родителя: вид неизменный, а состав заказа от
         * него зависит.
         */
        model.addAttribute("znpKinds", ZnpKind.values());

        /*
         * Изделия для группового и ремонтного видов: справочник
         * ограничивается типом, который требует вид (ячейка или
         * прибор), поэтому в форме нет списка «всего подряд».
         *
         * Источник вида, как и для серийников, выбирается по ситуации:
         * правка — вид редактируемого заказа, создание после ошибки
         * валидации — вид из самой формы, иначе отмеченное изделие
         * пропало бы вместе с ошибкой. Для комплектования справочник
         * пуст: там состав — перечень серийников.
         */
        ZnpKind formKind = resolveFormKind(model, editingOrder);

        model.addAttribute(
                "kindClassifiers",
                productionOrderService.findClassifierOptions(
                        formKind.requiredProductType()
                )
        );

        /*
         * Маршруты для группового заказа: выбор ограничен изделием,
         * поэтому список строится по выбранному изделию (см.
         * ZnpKind#requiresTechProcess).
         *
         * Источник изделия тот же, что у справочника изделий: правка —
         * изделие редактируемого заказа, создание после ошибки
         * валидации — значение самой формы, иначе выбранный маршрут
         * пропал бы вместе с ошибкой. Для комплектования список пуст:
         * маршрут там не нужен, операции берёт выпуск.
         */
        model.addAttribute(
                "techProcesses",
                productionOrderService.findTechProcessOptions(
                        formClassifierId(model, editingOrder)
                )
        );

        /*
         * Заказы верхнего уровня (ЗП) — выбор родителя для заказа-
         * комплектующего. Список отдаётся всегда, в том числе при правке:
         * уровень заказа неизменный, и в шаблоне родитель отображается
         * как справочное значение.
         */
        model.addAttribute(
                "rootOrders",
                productionOrderService.findRootOrders()
        );

        model.addAttribute(
                "kittingOperations",
                productionOrderService.findKittingOperations()
        );

        /*
         * Счётчик меню здесь не ставится: его считает
         * DispatcherViewAdvice по видам своего раздела. Прежняя строка
         * с countByActiveTrue() перезаписывала значение всем страницам
         * формы и показывала в пункте «Заказы на производство» число,
         * включавшее групповые заказы.
         */
        model.addAttribute("currentYear", LocalDate.now().getYear());
    }

    /**
     * Вид заказа, которым сейчас оперирует форма.
     *
     * Единый источник для подсветки меню и для справочника изделий:
     * раньше вид для справочника определялся отдельно и только при
     * наличии родителя, из-за чего правка группового заказа (родителя
     * у него нет, V38) брала комплектование и отдавала пустой список
     * изделий.
     *
     * При правке вид берётся из заказа независимо от родителя — вид
     * неизменный (см. assertKindUnchanged), поэтому заказ и есть
     * достоверный источник. При создании — из формы: туда его положил
     * обработчик открытия по параметру {@code kind}, и то же значение
     * переживает перерисовку после ошибки валидации.
     */
    private ZnpKind resolveFormKind(Model model,
                                    ProductionOrder editingOrder) {

        if (editingOrder != null) {
            return editingOrder.getZnpKind() == null
                    ? ZnpKind.KITTING
                    : editingOrder.getZnpKind();
        }

        Object boundForm = model.asMap().get("productionOrderForm");

        return boundForm instanceof ProductionOrderForm form
                ? form.getResolvedKind()
                : ZnpKind.KITTING;
    }

    /**
     * Изделие, выбранное в форме заказа.
     *
     * Нужно для списка маршрутов: техпроцесс выбирают по изделию, а
     * источник значения зависит от режима формы так же, как у
     * {@link #resolveFormKind} — при правке это изделие заказа, при
     * создании (в том числе после ошибки валидации) значение самой
     * формы.
     */
    private Long formClassifierId(Model model,
                                  ProductionOrder editingOrder) {

        if (editingOrder != null) {
            return editingOrder.getClassifierId();
        }

        Object boundForm = model.asMap().get("productionOrderForm");

        return boundForm instanceof ProductionOrderForm form
                ? form.getProductClassifierId()
                : null;
    }

    /**
     * Пункт меню, подсвечиваемый формой заказа.
     *
     * Групповой или ремонтный вид — «Групповые ЗНП», выпуск и
     * комплектование — «Заказы на производство»: те же наборы видов,
     * по которым разделены списки (см. ZnpKind#isGroupKind), поэтому
     * форма подсвечивает тот раздел, откуда заказ и виден.
     */
    private String formMenuSection(Model model,
                                   ProductionOrder editingOrder) {

        return resolveFormKind(model, editingOrder).isGroupKind()
                ? "groupZnp"
                : "productionOrder";
    }

    /**
     * Изделия для выбранного вида заказа-комплектующего.
     *
     * Справочник ограничен типом, который требует вид: групповой заказ
     * на ячейки обязан называть ячейку, заказ на приборы — прибор.
     * Ответ отдаётся по выбору вида, потому что вид переключают уже
     * после открытия формы, а держать в странице четыре списка сразу
     * означало бы показывать диспетчеру чужие изделия.
     */
    @GetMapping("/production-order/kind-classifiers")
    @ResponseBody
    public List<Map<String, Object>> kindClassifiers(
            @RequestParam(required = false) String kind
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (ProductClassifier classifier
                : productionOrderService.findClassifierOptions(
                        ZnpKind.ofNullable(kind).requiredProductType())) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", classifier.getId());
            row.put("code", classifier.getCode());
            row.put("name", classifier.getDisplayName());

            rows.add(row);
        }

        return rows;
    }

    /**
     * Маршруты изделия, выбранного в форме заказа.
     *
     * Групповой заказ обязан называть техпроцесс (см.
     * ZnpKind#requiresTechProcess), но список маршрутов зависит от
     * изделия, а изделие меняют уже в странице — поэтому список
     * отдаётся отдельно и только для выбранного классификатора.
     *
     * Пустой ответ при непустом изделии означает, что маршрутов у
     * изделия нет: диспетчер увидит пустой выбор и поймёт, что
     * маршрут ещё не настроен, — предлагать все маршруты подряд
     * значило бы дать поставить чужой техпроцесс.
     */
    @GetMapping("/production-order/classifier-routes")
    @ResponseBody
    public List<Map<String, Object>> classifierRoutes(
            @RequestParam(required = false) Long classifierId
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (ClassifierRouteOptionView route
                : productionOrderService.findTechProcessOptions(
                        classifierId)) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", route.getId());
            row.put("code", route.getCode());
            row.put("basic", route.isBasic());
            row.put("operationCount", route.getOperationCount());

            rows.add(row);
        }

        return rows;
    }

    /**
     * Номера датчиков выбранного заказа верхнего уровня.
     *
     * Серийники выпускает ЗП, а заказ-комплектующий только выбирается
     * среди них: свой список ЗНП не создаёт. Ответ отдаётся по выбору
     * родителя, чтобы форма не предлагала номера чужого выпуска.
     */
    @GetMapping("/production-order/parent-serials")
    @ResponseBody
    public List<Map<String, Object>> parentSerials(
            @RequestParam(required = false) Long parentId
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (ProductSerial serial
                : serialKittingService.findAvailableSerials(parentId)) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", serial.getId());
            row.put("number", serial.getFullNumber());
            row.put("label", serial.getDisplayLabel());
            row.put(
                    "typeName",
                    serial.getProductType() == null
                            ? ""
                            : serial.getProductType().getDisplayName()
            );

            rows.add(row);
        }

        return rows;
    }

    /**
     * Состав выпуска для предпросмотра по выбранному заказу покупателя.
     *
     * Серийные номера здесь не показываются: они выдаются только
     * при сохранении заказа, поэтому в превью виден код изделия
     * и число экземпляров, которые получат номера.
     */
    @GetMapping("/production-order/customer-order-items")
    @ResponseBody
    public List<Map<String, Object>> productionPreview(
            @RequestParam(required = false) Long customerOrderId
    ) {
        if (customerOrderId == null) {
            return Collections.emptyList();
        }

        CustomerOrder customerOrder = customerOrderRepository
                .findFullById(customerOrderId)
                .orElse(null);

        if (customerOrder == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();

        for (CustomerOrderItem item : customerOrder.getItems()) {
            ProductClassifier classifier = item.getProductClassifier();

            if (classifier == null) {
                continue;
            }

            int instances = productionOrderService.instanceCount(
                    item.getQuantity()
            );

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("classifierCode", classifier.getCode());
            row.put("productTypeName", classifier.getProductType() == null
                    ? "—"
                    : classifier.getProductType().getDisplayName());
            row.put("productName", classifier.getDisplayName());
            row.put("quantity", formatQuantity(item.getQuantity()));
            row.put("instances", instances);

            rows.add(row);
        }

        return rows;
    }

    /**
     * Серийники выпуска, которые ещё можно ему присвоить.
     *
     * Перечень строит резервы выбранного заказа покупателя: номера,
     * уже выпущенные другим заказом или уже вошедшие в заказ-
     * комплектующий, в список не попадают — выпуск не должен отнимать
     * номер у действующего изделия. Типы ограничены датчиками и
     * системами: остальное выпуску не принадлежит.
     *
     * Полное имя берётся из позиции договора, а не из классификатора:
     * диспетчер сверяет номер с конструкторской документацией, где
     * изделие записано полным обозначением с параметрами.
     */
    @GetMapping("/production-order/release-serials")
    @ResponseBody
    public List<Map<String, Object>> releaseSerials(
            @RequestParam(required = false) Long customerOrderId
    ) {
        if (customerOrderId == null) {
            return Collections.emptyList();
        }

        List<ProductSerial> serials = productSerialRepository
                .findAssignableReleaseDetailsByCustomerOrderId(
                        customerOrderId,
                        List.of(ProductType.SENSOR, ProductType.SYSTEM)
                );

        List<Map<String, Object>> rows = new ArrayList<>();

        for (ProductSerial serial : serials) {

            CustomerOrderItem item = serial.getCustomerOrderItem();

            String fullName = item == null ? null : item.getFullName();

            if (fullName == null || fullName.isBlank()) {
                fullName = serial.getDisplayName();
            }

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", serial.getId());
            row.put("number", serial.getFullNumber());
            row.put("fullName", fullName);
            row.put("typeName", serial.getTypeName());
            row.put("itemId", item == null ? null : item.getId());
            row.put("znpNumber", serial.getZnpNumber());

            rows.add(row);
        }

        return rows;
    }

    /**
     * Состав системы для раскрытия полей выбора датчиков и приборов.
     *
     * Вызывается из формы заказа асинхронно при выборе изделия
     * с типом «Система».
     */
    @GetMapping("/customer-order/system-components")
    @ResponseBody
    public List<Map<String, Object>> systemComponents(
            @RequestParam(required = false) Long systemId
    ) {
        if (systemId == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> components = new ArrayList<>();

        for (SystemComponentProjection component
                : customerOrderService.findSystemComponents(systemId)) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("sensorCatalogId", component.getSensorCatalogId());
            row.put("sensorCatalogName", component.getSensorCatalogName());
            row.put(
                    "componentClassifierId",
                    component.getComponentClassifierId()
            );
            row.put("componentCode", component.getComponentCode());
            row.put("componentName", component.getComponentName());

            components.add(row);
        }

        return components;
    }

    /**
     * Карточки количества заказанной продукции по типам изделий.
     */
    /*
     * Серийные номера карточки заказа: позиция состава → перечень
     * полных номеров через запятую.
     *
     * Один запрос на всю карточку (open-in-view выключен): номера
     * приходят отсортированными по позиции и серии, поэтому
     * группировка тривиальна. Позиции без номеров в карту не
     * попадают — шаблон печатает по ним прочерк.
     *
     * Перечень не сокращается: карточка заказа — то место, где
     * диспетчеру нужен полный список зарезервированных серий,
     * а не подпись для узкой колонки очереди.
     */
    private Map<Integer, String> serialsByItemPosition(Long orderId) {

        Map<Integer, List<String>> numbers = new LinkedHashMap<>();

        for (ProductSerial serial :
                productSerialRepository.findDetailsByCustomerOrderId(orderId)) {

            CustomerOrderItem item = serial.getCustomerOrderItem();

            if (item == null || item.getPosition() == null) {
                continue;
            }

            numbers
                    .computeIfAbsent(item.getPosition(), key -> new ArrayList<>())
                    .add(serial.getFullNumber());
        }

        Map<Integer, String> labels = new LinkedHashMap<>();

        numbers.forEach((position, list) ->
                labels.put(position, String.join(", ", list)));

        return labels;
    }

    private List<TypeCardView> buildTypeCards() {

        Map<ProductType, BigDecimal> totals = new EnumMap<>(
                ProductType.class
        );

        for (ProductTypeQuantityProjection row
                : customerOrderItemRepository
                .sumQuantityGroupedByProductType()) {

            if (row.getProductType() == null) {
                continue;
            }

            totals.put(
                    row.getProductType(),
                    row.getTotalQuantity() == null
                            ? BigDecimal.ZERO
                            : row.getTotalQuantity()
            );
        }

        List<TypeCardView> cards = new ArrayList<>();

        for (int index = 0; index < TYPE_ORDER.length; index++) {
            ProductType type = TYPE_ORDER[index];

            cards.add(new TypeCardView(
                    type.name(),
                    type.getDisplayName(),
                    totals.getOrDefault(type, BigDecimal.ZERO).longValue(),
                    TypeCards.icon(type.getDisplayName()),
                    TypeCards.modifier(index)
            ));
        }

        return cards;
    }

    /**
     * Количество без лишних нулей — для карточки обзора.
     */
    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return "0";
        }

        return quantity.stripTrailingZeros().toPlainString();
    }

    /**
     * Числовое поле формы: допускаются запятая и точка
     * как разделитель дробной части.
     */
    static class QuantityEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            if (text == null || text.isBlank()) {
                setValue(null);
                return;
            }

            try {
                setValue(new BigDecimal(
                        text.trim().replace(',', '.')
                ));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Введите число, например 10 или 10,5"
                );
            }
        }
    }

    /**
     * Поле даты формы в формате дд.мм.гггг.
     */
    static class RuDateEditor extends PropertyEditorSupport {

        private static final DateTimeFormatter FORMAT =
                DateTimeFormatter.ofPattern("dd.MM.yyyy");

        @Override
        public void setAsText(String text) {
            if (text == null || text.isBlank()) {
                setValue(null);
                return;
            }

            try {
                setValue(LocalDate.parse(text.trim(), FORMAT));
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "Введите дату в формате дд.мм.гггг"
                );
            }
        }
    }


    /**
     * Комплектовочные операции выбранного серийника.
     *
     * Список зависит от изделия серийника, а серийник меняют уже в
     * карточке выпуска — поэтому операции отдаются отдельно и только
     * для выбранного номера.
     *
     * Вместе с операцией отдаётся и уже внесённый номер ЗНП: диспетчер
     * обязан увидеть прежнее значение в поле при повторном открытии
     * строки, иначе решит, что заказ не заводился, и заведёт дубль.
     */
    @GetMapping("/production-order/serial-kitting-operations")
    @ResponseBody
    public List<Map<String, Object>> serialKittingOperations(
            @RequestParam(required = false) Long serialId
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();

        Map<Long, String> znpNumbers =
                serialKittingService.findZnpNumbersByOperation(serialId);

        int position = 1;

        for (Operation operation
                : productionOrderService
                        .findSerialKittingOperations(serialId)) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", operation.getId());
            row.put(
                    "name",
                    operation.getOperationName() == null
                            ? ""
                            : operation.getOperationName().getName()
            );
            row.put("position", position++);
            row.put("znpNumber", znpNumbers.get(operation.getId()));

            rows.add(row);
        }

        return rows;
    }

    /**
     * Создаёт заказы-комплектующие по отмеченным парам
     * «серийник + операция» внутри выпуска.
     */
    @PostMapping("/production-order/{id}/kitting-orders")
    @ResponseBody
    public Map<String, Object> createKittingOrders(
            @PathVariable Long id,
            @RequestBody List<Map<String, Object>> pairs,
            Authentication authentication
    ) {
        AppUser author = authentication == null
                ? null
                : userRepository.findByUsernameIgnoreCase(
                        authentication.getName()
                ).orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<ProductionOrder> created =
                    productionOrderService.createKittingOrders(
                            id, pairs, author);

            List<Map<String, Object>> rows = new ArrayList<>();

            for (ProductionOrder order : created) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", order.getId());
                row.put("number", order.getNumber());
                rows.add(row);
            }

            response.put("ok", true);
            response.put("created", rows);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            response.put("ok", false);
            response.put("error", exception.getMessage());
        }

        return response;
    }

}