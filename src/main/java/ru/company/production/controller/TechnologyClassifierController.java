package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.ClassifierForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.InclusionMode;
import ru.company.production.entity.MeasurementUnit;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.ActiveRouteOptionView;
import ru.company.production.repository.ClassifierRouteLinkView;
import ru.company.production.repository.ClassifierRouteOptionView;
import ru.company.production.service.ClassifierRouteService;
import ru.company.production.service.ProductClassifierService;
import ru.company.production.service.TechProcessService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/technology/classifier")
@RequiredArgsConstructor
public class TechnologyClassifierController {

    private final ProductClassifierService classifierService;
    private final TechProcessService techProcessService;
    private final ClassifierRouteService routeService;

    /**
     * Порядок типов на главной странице классификатора.
     */
    private static final ProductType[] TYPE_ORDER = {
            ProductType.CELL,
            ProductType.SENSOR,
            ProductType.DEVICE,
            ProductType.SYSTEM
    };

    @GetMapping
    public String classifierPage(
            @RequestParam(
                    name = "type",
                    required = false
            )
            String type,
            Model model
    ) {
        fillCommonModel(model, null);

        List<ProductClassifier> all = classifierService.findAll();
        ProductType activeType = parseType(type);

        model.addAttribute(
                "classifiers",
                activeType == null
                        ? all
                        : all.stream()
                            .filter(item ->
                                    item.getProductType() == activeType)
                            .toList()
        );

        model.addAttribute(
                "activeType",
                activeType == null ? null : activeType.name()
        );
        model.addAttribute("totalCount", all.size());

        fillClassifierCards(model, all);
        fillRouteColumns(model, all);

        return "technology/classifier/index";
    }

    /**
     * Разбирает параметр типа из адресной строки.
     * Некорректное значение трактуется как «показать все».
     */
    private ProductType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }

        try {
            return ProductType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Строит карточки-фильтры по типам изделий.
     */
    private void fillClassifierCards(
            Model model,
            List<ProductClassifier> all
    ) {
        List<TypeCardView> cards = new ArrayList<>();

        for (int index = 0; index < TYPE_ORDER.length; index++) {
            ProductType type = TYPE_ORDER[index];
            long count = all.stream()
                    .filter(item -> item.getProductType() == type)
                    .count();

            cards.add(new TypeCardView(
                    type.name(),
                    type.getDisplayName(),
                    count,
                    TypeCards.icon(type.getDisplayName()),
                    TypeCards.modifier(index)
            ));
        }

        model.addAttribute("classifierCards", cards);
    }

    /**
     * Готовит данные колонок «Техпроцессы» и базового маршрута.
     *
     * Группировку делают на стороне приложения, а не в шаблоне: сотни
     * изделий, каждый обход которых перебирал бы весь список
     * маршрутов, дали бы миллионы сравнений на одну отрисовку.
     * Маршруты читают одним запросом (см. ClassifierRouteService),
     * поэтому список не уходит в N+1.
     */
    private void fillRouteColumns(
            Model model,
            List<ProductClassifier> all
    ) {
        Map<Long, List<ClassifierRouteLinkView>> byClassifier =
                new LinkedHashMap<>();

        for (ClassifierRouteLinkView link
                : routeService.findAllActiveRoutes()) {

            byClassifier
                    .computeIfAbsent(
                            link.getClassifierId(),
                            key -> new ArrayList<>()
                    )
                    .add(link);
        }

        Map<Long, String> basicCode = new HashMap<>();

        for (ProductClassifier classifier : all) {
            byClassifier
                    .getOrDefault(classifier.getId(), List.of())
                    .stream()
                    .filter(ClassifierRouteLinkView::isBasic)
                    .findFirst()
                    .ifPresent(link -> basicCode.put(
                            classifier.getId(),
                            link.getCode()
                    ));
        }

        model.addAttribute("routesByClassifier", byClassifier);
        model.addAttribute("basicRouteCode", basicCode);
    }

    /**
     * Страница создания классификатора.
     */
    @GetMapping("/create")
    public String createClassifierPage(Model model) {
        if (!model.containsAttribute("classifierForm")) {
            model.addAttribute(
                    "classifierForm",
                    new ClassifierForm()
            );
        }

        fillCommonModel(model, null);

        return "technology/classifier/create";
    }

    /**
     * Сохранение нового классификатора.
     */
    @PostMapping("/create")
    public String createClassifier(
            @Valid
            @ModelAttribute("classifierForm")
            ClassifierForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        validateConditionalFields(form, errors);

        if (!errors.hasErrors()) {
            try {
                classifierService.create(form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "classifier.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model, null);

            return "technology/classifier/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно добавлен"
        );

        return "redirect:/technology/classifier";
    }

    /**
     * Страница выбора классификатора для изменения.
     */
    @GetMapping("/update")
    public String updateClassifierSelectionPage(Model model) {
        if (!model.containsAttribute("classifierForm")) {
            model.addAttribute(
                    "classifierForm",
                    new ClassifierForm()
            );
        }

        fillCommonModel(model, null);

        return "technology/classifier/update";
    }

    /**
     * Страница изменения выбранного классификатора.
     */
    @GetMapping("/update/{id}")
    public String updateClassifierPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifierForm",
                    classifierService.getForm(id)
            );

            model.addAttribute(
                    "selectedClassifierId",
                    id
            );

            fillCommonModel(model, id);

            return "technology/classifier/update";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/update";
        }
    }

    /**
     * Сохранение изменений классификатора.
     */
    @PostMapping("/update/{id}")
    public String updateClassifier(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("classifierForm")
            ClassifierForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        form.setId(id);

        /*
         * Идентификатор нужен для повторного отображения формы,
         * если сервер вернул ошибку валидации.
         */
        model.addAttribute(
                "selectedClassifierId",
                id
        );

        validateConditionalFields(form, errors);

        if (!errors.hasErrors()) {
            try {
                classifierService.update(id, form);
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException |
                    IllegalStateException exception
            ) {
                errors.reject(
                        "classifier.save",
                        exception.getMessage()
                );
            }
        }

        if (errors.hasErrors()) {
            fillCommonModel(model, id);

            return "technology/classifier/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Классификатор успешно изменён"
        );

        return "redirect:/technology/classifier";
    }

    /**
     * Страница выбора классификатора для удаления.
     */
    @GetMapping("/delete")
    public String deleteClassifierSelectionPage(Model model) {
        fillCommonModel(model, null);

        return "technology/classifier/delete";
    }

    /**
     * Страница подтверждения удаления выбранного классификатора.
     */
    @GetMapping("/delete/{id}")
    public String deleteClassifierPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifierForm",
                    classifierService.getForm(id)
            );

            model.addAttribute(
                    "selectedClassifierId",
                    id
            );

            fillCommonModel(model, id);

            return "technology/classifier/delete";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/delete";
        }
    }

    /**
     * Удаление классификатора.
     */
    @PostMapping("/delete/{id}")
    public String deleteClassifier(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            classifierService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Классификатор успешно удалён"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/technology/classifier";
    }

    /**
     * Страница «Добавить техпроцесс» для изделия.
     *
     * Маршрут не дублируют: изделию назначают уже существующий
     * техпроцесс либо ведут технолога в конструктор нового.
     *
     * В список выбора не попадают маршруты, которые изделию уже
     * применены: сервис и так откажет дублем, но показывать их
     * бессмысленно.
     */
    @GetMapping("/{id}/tp")
    public String addTechProcessPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ProductClassifier classifier =
                    classifierService.findOne(id);

            List<ClassifierRouteOptionView> applied =
                    techProcessService.findRoutesForClassifier(id);

            Set<Long> appliedIds = applied.stream()
                    .map(ClassifierRouteOptionView::getId)
                    .collect(Collectors.toSet());

            List<ActiveRouteOptionView> options =
                    routeService.findAllActiveRouteOptions()
                            .stream()
                            .filter(option ->
                                    !appliedIds.contains(
                                            option.getProcessId()))
                            .toList();

            model.addAttribute("classifier", classifier);
            model.addAttribute("routeOptions", options);
            model.addAttribute("appliedRoutes", applied);

            fillRoutePageModel(model);

            return "technology/classifier/routes";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier";
        }
    }

    /**
     * Применение существующего маршрута к изделию.
     *
     * Галочка «сделать базовым» передаётся флагом asBasic; без неё
     * маршрут остаётся обычным применённым. Иначе выпуск изделия
     * молча переключался бы на последний добавленный маршрут, а
     * технолог терял бы основной маршрут, который не менял.
     *
     * Привязка и перенос базовости выполняются одним действием
     * сервиса: при отказе переноса откатывается и привязка, чтобы
     * изделие не осталось с маршрутом, но со старым базовым.
     */
    @PostMapping("/{id}/tp")
    public String addTechProcess(
            @PathVariable Long id,
            @RequestParam Long processId,
            @RequestParam(
                    name = "asBasic",
                    defaultValue = "false"
            )
            boolean asBasic,
            RedirectAttributes redirectAttributes
    ) {
        try {
            routeService.applyRoute(id, processId, asBasic);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    asBasic
                            ? "Техпроцесс добавлен к изделию "
                                    + "и стал базовым"
                            : "Техпроцесс добавлен к изделию"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/" + id + "/tp";
        }

        return "redirect:/technology/classifier";
    }

    /**
     * Страница «Изменить базовый техпроцесс» изделия.
     */
    @GetMapping("/{id}/tp/basic")
    public String basicTechProcessPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "classifier",
                    classifierService.findOne(id)
            );

            model.addAttribute(
                    "appliedRoutes",
                    techProcessService.findRoutesForClassifier(id)
            );

            fillRoutePageModel(model);

            return "technology/classifier/basic";
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier";
        }
    }

    /**
     * Перенос базовости на выбранный маршрут изделия.
     *
     * Прежняя отметка снимается в сервисе: базовый маршрут у изделия
     * обязан остаться единственным.
     */
    @PostMapping("/{id}/tp/basic")
    public String changeBasicTechProcess(
            @PathVariable Long id,
            @RequestParam Long processId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            techProcessService.makeBasicForClassifier(
                    processId,
                    id
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Базовый техпроцесс изделия изменён"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/technology/classifier/"
                    + id + "/tp/basic";
        }

        return "redirect:/technology/classifier";
    }

    /**
     * Дополнительная проверка полей,
     * зависящих от выбранного типа изделия.
     */
    private void validateConditionalFields(
            ClassifierForm form,
            BindingResult errors
    ) {
        ProductType productType = form.getProductType();

        if (productType == null) {
            errors.rejectValue(
                    "productType",
                    "classifier.productType.required",
                    "Выберите тип изделия"
            );

            return;
        }

        if (productType == ProductType.SENSOR) {
            if (form.getSensorCatalogId() == null) {
                errors.rejectValue(
                        "sensorCatalogId",
                        "classifier.sensor.required",
                        "Выберите датчик из каталога"
                );
            }

            return;
        }

        if (form.getName() == null || form.getName().isBlank()) {
            errors.rejectValue(
                    "name",
                    "classifier.name.required",
                    "Укажите наименование классификатора"
            );
        }
    }

    /**
     * Минимальная модель страниц работы с техпроцессами изделия.
     *
     * Общую fillCommonModel здесь не вызывают: она тянет полный
     * список классификаторов с составом для форм выбора, а на
     * страницах «Добавить ТП» и «Изменить базовый ТП» таких форм нет —
     * лишний тяжёлый запрос на каждое открытие страницы.
     */
    private void fillRoutePageModel(Model model) {
        model.addAttribute("activePage", "classifier");
    }

    /**
     * Добавляет в модель общие данные,
     * используемые страницами классификатора.
     */
    private void fillCommonModel(
            Model model,
            Long excludedClassifierId
    ) {
        model.addAttribute(
                "activePage",
                "classifier"
        );

        model.addAttribute(
                "inclusionModes",
                InclusionMode.values()
        );

        model.addAttribute(
                "productTypes",
                ProductType.values()
        );

        model.addAttribute(
                "measurementUnits",
                MeasurementUnit.values()
        );

        model.addAttribute(
                "sensorCatalogOptions",
                classifierService.findSensors()
        );

        model.addAttribute(
                "classifiers",
                classifierService.findAll()
        );

        model.addAttribute(
                "inclusionOptions",
                classifierService.inclusionOptions(
                        excludedClassifierId
                )
        );

        model.addAttribute(
                "deviceOptions",
                classifierService.deviceOptions(
                        excludedClassifierId
                )
        );
    }
}