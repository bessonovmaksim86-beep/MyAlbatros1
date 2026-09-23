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
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.RepairOrderForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.RepairOrder;
import ru.company.production.entity.RepairOrderType;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.QualityService;

import java.util.List;

/**
 * Служба качества: разбор изолятора брака и заказы на ремонт (ЗНП).
 *
 * Маршрут построен вокруг отмеченных ячеек, а не вокруг заказа:
 * руководитель сначала отбирает ячейки в перечне изолятора, и только
 * потом выбирает назначение заказа и поставщика. Поэтому перечень
 * передаёт состав на второй шаг формы, а заказ создают отдельной
 * отправкой — повторный F5 на карточке не должен заводить второй заказ
 * на те же номера.
 *
 * Переход «перечень → форма заказа» сделан POST'ом: состав изолятора
 * считают и по «вся партия», где номеров в партии сотни, и в виде
 * строки запроса такой перечень упёрся бы в предел длины адреса.
 *
 * Правила отбора и формирования заказа находятся в
 * {@link QualityService}; контроллер превращает отказ в сообщение и
 * возвращает уже отмеченный состав, чтобы из-за невыбранного
 * поставщика руководитель не размечал изолятор заново.
 */
@Controller
@RequestMapping("/quality")
@RequiredArgsConstructor
public class QualityController {

    private final QualityService qualityService;
    private final UserRepository userRepository;

    /** Главная страница модуля: сводка службы и переход к разбору. */
    @GetMapping({"", "/", "/general", "/general/"})
    public String general(Model model) {

        model.addAttribute("activePage", "general");
        model.addAttribute("orders", qualityService.findOrders());
        model.addAttribute("openOrders", qualityService.countOpenOrders());
        model.addAttribute("inWorkOrders", qualityService.countInWorkOrders());
        model.addAttribute("doneOrders", qualityService.countDoneOrders());

        return "quality/general";
    }

    /**
     * Перечень изолятора брака: ячейки с отбором и отметкой выбора.
     *
     * Отбор задают тремя условиями — разновидность изделия, его
     * классификатор и причина брака; выбранные значения возвращают в
     * модель, чтобы после перехода по ссылке форма фильтра не
     * «забывала» отбор.
     */
    @GetMapping({"/quarantine", "/quarantine/"})
    public String quarantine(
            @RequestParam(name = "kind", required = false) String kindValue,
            @RequestParam(name = "classifierId", required = false) Long classifierId,
            @RequestParam(name = "reasonId", required = false) Long reasonId,
            Model model
    ) {
        QuarantineFilter filter =
                new QuarantineFilter(parseKind(kindValue), classifierId, reasonId);

        model.addAttribute("activePage", "quarantine");
        model.addAttribute("cells", qualityService.findCells(filter));
        model.addAttribute("filterKind", filter.kind());
        model.addAttribute("filterClassifierId", filter.classifierId());
        model.addAttribute("filterReasonId", filter.reasonId());
        model.addAttribute("kinds", QuarantineCellKind.values());
        model.addAttribute(
                "classifierOptions",
                qualityService.findClassifierOptions()
        );
        model.addAttribute("reasonOptions", qualityService.findReasonOptions());

        return "quality/quarantine/index";
    }

    /**
     * Второй шаг разбора: по отмеченным ячейкам выбирают вид заказа
     * и поставщика.
     *
     * Страница ещё ничего не записывает — она показывает состав
     * будущего заказа и форму выбора. Состав читают по ключам
     * перечня: пару «группа | номер» форма получает без повторного
     * обхода ленивых связей.
     */
    @PostMapping("/orders/new")
    public String newOrder(
            @RequestParam(name = "cells", required = false) List<String> cells,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        List<QualityService.QuarantineCellRow> picked;

        try {
            picked = qualityService.findPickedCells(cells);
        } catch (IllegalStateException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/quality/quarantine";
        }

        if (picked.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Отметьте в изоляторе хотя бы одну ячейку"
            );

            return "redirect:/quality/quarantine";
        }

        RepairOrderForm form = new RepairOrderForm();
        form.setCells(picked.stream().map(QualityService.QuarantineCellRow::getCellKey).toList());
        form.setOrderType(
                QualityService.suggestOrderType(picked) == null
                        ? null
                        : QualityService.suggestOrderType(picked).name()
        );

        renderNewOrder(model, form, picked);

        return "quality/orders/new";
    }

    /** Реестр заказов на ремонт. */
    @GetMapping({"/orders", "/orders/"})
    public String orders(Model model) {

        model.addAttribute("activePage", "orders");
        model.addAttribute("orders", qualityService.findOrders());

        return "quality/orders/index";
    }

    /** Карточка заказа: состав ячеек и переходы по статусу. */
    @GetMapping("/orders/{id}")
    public String order(@PathVariable Long id,
                        Model model,
                        RedirectAttributes redirectAttributes) {

        try {
            model.addAttribute("activePage", "orders");
            model.addAttribute("order", qualityService.getOrder(id));
            model.addAttribute("items", qualityService.findOrderItems(id));
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/quality/orders";
        }

        return "quality/orders/card";
    }

    /**
     * Создаёт заказ на ремонт по отмеченным ячейкам.
     *
     * При отказе форма возвращается тем же методом (не redirect'ом) с
     * уже отмеченным составом: перечень размечают долго, и терять его
     * из-за невыбранного поставщика незачем. Успех уходит redirect'ом
     * на карточку заказа — повторная отправка не создала бы второй
     * заказ на те же номера.
     */
    @PostMapping("/orders")
    public String create(
            @Valid @ModelAttribute("repairOrderForm") RepairOrderForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        List<QualityService.QuarantineCellRow> picked;

        try {
            picked = qualityService.findPickedCells(form.getCells());
        } catch (IllegalStateException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/quality/quarantine";
        }

        if (picked.isEmpty() || errors.hasErrors()) {
            renderNewOrder(model, form, picked);
            model.addAttribute(
                    "errorMessage",
                    errors.hasErrors()
                            ? errors.getAllErrors().get(0).getDefaultMessage()
                            : "Отметьте в изоляторе хотя бы одну ячейку"
            );

            return "quality/orders/new";
        }

        RepairOrder order;

        try {
            order = qualityService.create(form, currentUser(authentication));
        } catch (IllegalArgumentException exception) {
            renderNewOrder(model, form, picked);
            model.addAttribute("errorMessage", exception.getMessage());

            return "quality/orders/new";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ «" + order.getNumber() + "» сформирован: ячеек — "
                        + order.getItems().size()
        );

        return "redirect:/quality/orders/" + order.getId();
    }

    /** Берёт заказ в работу. */
    @PostMapping("/orders/{id}/start")
    public String start(@PathVariable Long id,
                        RedirectAttributes redirectAttributes) {
        return changeStatus(id, redirectAttributes, "в работу",
                qualityService::start);
    }

    /** Закрывает заказ как выполненный. */
    @PostMapping("/orders/{id}/complete")
    public String complete(@PathVariable Long id,
                           RedirectAttributes redirectAttributes) {
        return changeStatus(id, redirectAttributes, "выполненным",
                qualityService::complete);
    }

    /** Отменяет заказ: ячейки возвращаются в изолятор. */
    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         RedirectAttributes redirectAttributes) {
        return changeStatus(id, redirectAttributes, "отменённым",
                qualityService::cancel);
    }

    /**
     * Переход по статусу с возвратом на карточку.
     *
     * Отказ сервиса (закрытый заказ отменить нельзя и т. п.)
     * возвращается сообщением на той же карточке: состояние заказа
     * видно на экране, и уводить руководителя в реестр незачем.
     */
    private String changeStatus(Long id,
                                RedirectAttributes redirectAttributes,
                                String label,
                                java.util.function.Function<Long, RepairOrder> action) {
        try {
            RepairOrder order = action.apply(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Заказ «" + order.getNumber() + "» стал статусом «"
                            + order.getStatusDisplayName() + "»"
            );
        } catch (EntityNotFoundException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/quality/orders/" + id;
    }

    /** Данные страницы выбора поставщика: состав, вид заказа, справочник. */
    private void renderNewOrder(
            Model model,
            RepairOrderForm form,
            List<QualityService.QuarantineCellRow> picked
    ) {
        model.addAttribute("activePage", "quarantine");
        model.addAttribute("pickedCells", picked);
        model.addAttribute("cellCount", picked.size());
        model.addAttribute("orderTypes", RepairOrderType.values());
        model.addAttribute("suppliers", qualityService.findSuppliers());
        model.addAttribute("repairOrderForm", form);
    }

    /**
     * Разновидность изделия из строки запроса.
     *
     * Неизвестное значение трактуют как «не выбрано»: ссылку фильтра
     * могли передать вручную или от устаревшей версии перечня, и
     * падать из-за этого страница не обязана.
     */
    private static QuarantineCellKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return QuarantineCellKind.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Руководитель службы — автор заказа: заказ на ремонт подписывают
     * сотрудником, который его оформил.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя службы качества не найдена"
                ));
    }
}
