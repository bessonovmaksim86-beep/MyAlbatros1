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
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.TechProcessType;
import ru.company.production.repository.CustomerOrderItemRepository;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.ProductTypeQuantityProjection;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.CustomerOrderService;
import ru.company.production.service.CustomerService;
import ru.company.production.service.ProductionOrderService;
import ru.company.production.service.SerialKittingService;

import java.beans.PropertyEditorSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final CustomerOrderService customerOrderService;
    private final CustomerService customerService;
    private final UserRepository userRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderService productionOrderService;

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
         */
        model.addAttribute(
                "productionOrderCount",
                productionOrderRepository.countByActiveTrue()
        );

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
    public String createPage(Model model) {

        if (!model.containsAttribute("customerOrderForm")) {
            model.addAttribute(
                    "customerOrderForm",
                    newOrderForm()
            );
        }

        fillFormModel(model);

        return "dispatcher/order/create";
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
                productionOrderRepository.findAllListItems(
                        ProductionItemStatus.DONE
                )
        );

        model.addAttribute(
                "productionOrderCount",
                productionOrderRepository.countByActiveTrue()
        );

        model.addAttribute(
                "productionOrderNewCount",
                productionOrderRepository.countActiveByStatus(
                        ProductionOrderStatus.NEW
                )
        );

        model.addAttribute(
                "productionOrderInWorkCount",
                productionOrderRepository.countActiveByStatus(
                        ProductionOrderStatus.IN_WORK
                )
        );

        model.addAttribute(
                "productionOrderDoneCount",
                productionOrderRepository.countActiveByStatus(
                        ProductionOrderStatus.DONE
                )
        );

        return "dispatcher/order/production";
    }

    /**
     * Форма создания заказа на производство.
     *
     * Состав выпуска в форме не редактируется: он выведется из позиций
     * выбранного заказа покупателя при сохранении, и каждая позиция
     * получит серийный номер.
     */
    @GetMapping("/production-order/create")
    public String createProductionPage(Model model) {

        if (!model.containsAttribute("productionOrderForm")) {
            model.addAttribute(
                    "productionOrderForm",
                    newProductionOrderForm()
            );
        }

        fillProductionFormModel(model);

        return "dispatcher/order/production-create";
    }

    /**
     * Сохранение заказа на производство.
     */
    @PostMapping("/production-order/create")
    public String createProduction(
            @Valid
            @ModelAttribute("productionOrderForm")
            ProductionOrderForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        /*
         * Автоподбор номера работает только для заказа верхнего уровня.
         * Заказу-комплектующему номер обязателен ручной ввод: он
         * приходит из внешнего учёта, и подставленный по шаблону номер
         * не соответствовал бы документу, а сервис уже не смог бы
         * отличить пропуск поля от намеренного значения.
         */
        if (form.getParentProductionOrderId() == null
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

        boolean component = form.getParentProductionOrderId() != null;

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

        return "redirect:/dispatcher/production-order";
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
        model.addAttribute("activePage", "productionOrder");

        model.addAttribute(
                "productionOrderCount",
                productionOrderRepository.countByActiveTrue()
        );

        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElse(null);

        if (order == null) {
            model.addAttribute(
                    "errorMessage",
                    "Заказ на производство не найден"
            );

            return "dispatcher/order/production-view";
        }

        model.addAttribute("order", order);

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
         */
        model.addAttribute(
                "children",
                order.isRoot()
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

        return "dispatcher/order/production-view";
    }

    /**
     * Форма правки заказа на производство.
     *
     * Состав выпуска не правится: серийные номера уже выданы,
     * меняется только шапка заказа.
     */
    @GetMapping("/production-order/{id}/edit")
    public String editProductionPage(
            @PathVariable Long id,
            Model model
    ) {
        ProductionOrder order = productionOrderRepository
                .findDetailedById(id)
                .orElse(null);

        if (order == null) {
            return "redirect:/dispatcher/production-order";
        }

        ProductionOrderForm form = editProductionOrderForm(order);

        /*
         * Для комплектующего возвращаются уже выбранные датчики:
         * состав живёт отдельной связью (V20) и в шапке заказа не
         * хранится, без явной передачи форма правки показалась бы
         * пустой, а сохранение сняло бы весь состав.
         */
        form.setSerialIds(
                serialKittingService.findSerialIds(order.getId())
        );

        model.addAttribute("productionOrderForm", form);
        model.addAttribute("editing", true);

        fillProductionFormModel(model, order);

        return "dispatcher/order/production-create";
    }

    /**
     * Сохранение изменений заказа на производство.
     */
    @PostMapping("/production-order/{id}/edit")
    public String editProduction(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("productionOrderForm")
            ProductionOrderForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        form.setId(id);

        AppUser author = authentication == null
                ? null
                : userRepository.findByUsernameIgnoreCase(
                        authentication.getName()
                ).orElse(null);

        if (!errors.hasErrors()) {
            try {
                productionOrderService.update(id, form, author);
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
            model.addAttribute("editing", true);

            /*
             * Справочники правки перезаполняются вместе с редактируемым
             * заказом: его заказчик занят выпуском и без явной передачи
             * в список свободных не попал бы — select отрисовался бы
             * пустым.
             */
            fillProductionFormModel(
                    model,
                    productionOrderRepository.findDetailedById(id)
                            .orElse(null)
            );

            return "dispatcher/order/production-create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ на производство «"
                        + (form.getNumber() == null
                        ? ""
                        : form.getNumber().trim())
                        + "» успешно изменён"
        );

        return "redirect:/dispatcher/production-order/" + id;
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
     * Удаление заказа на производство.
     */
    @PostMapping("/production-order/{id}/delete")
    public String deleteProduction(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
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

        return "redirect:/dispatcher/production-order";
    }

    /**
     * Переход после действия над заказом: ошибка возвращается
     * на страницу заказа, успех — тоже на неё, чтобы был виден
     * новый статус.
     */
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
     * Значения формы выпуска по умолчанию: номер ПЗ-ГГГГ-NNN
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

        return form;
    }

    /**
     * Форма правки выпуска: состав не переносится — он уже
     * сформирован и закреплён серийными номерами.
     */
    private ProductionOrderForm editProductionOrderForm(
            ProductionOrder order
    ) {
        ProductionOrderForm form = new ProductionOrderForm();

        form.setId(order.getId());
        form.setVersion(order.getVersion());
        form.setNumber(order.getNumber());
        form.setCustomerOrderId(
                order.getCustomerOrder() == null
                        ? null
                        : order.getCustomerOrder().getId()
        );
        /*
         * Родитель переносится обязательно: без него форма правки
         * заказала бы выпуск верхнего уровня, и сервис отклонил бы
         * сохранение как попытку сменить уровень заказа.
         */
        form.setParentProductionOrderId(
                order.getParentProductionOrder() == null
                        ? null
                        : order.getParentProductionOrder().getId()
        );
        form.setProductionOperationId(
                order.getProductionOperation() == null
                        ? null
                        : order.getProductionOperation().getId()
        );
        form.setOrderDate(order.getOrderDate());
        form.setNote(order.getNote());

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
        model.addAttribute("activePage", "productionOrder");

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

        model.addAttribute(
                "productionOrderCount",
                productionOrderRepository.countByActiveTrue()
        );

        model.addAttribute("currentYear", LocalDate.now().getYear());
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
}