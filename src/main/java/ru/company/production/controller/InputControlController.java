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
import ru.company.production.dto.InputControlForm;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.InputControlCard;
import ru.company.production.entity.InputControlDecision;
import ru.company.production.entity.InputControlType;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlService;

/**
 * Бюро входного контроля: проверка оприходованных партий.
 *
 * Маршрут построен вокруг карточки, а не партии: партию «берут в
 * работу» (открывают карточку), дальше работают в ней. Поэтому адрес
 * карточки постоянный, а сохранение идёт redirect'ом — повторный F5 не
 * должен закрыть проверку второй раз и задвоить номера в изоляторе
 * брака.
 *
 * Правила проверки партии целиком находятся в
 * {@link InputControlService}; контроллер превращает отказ в сообщение
 * и возвращает оператору уже введённые номера, чтобы из-за одной
 * опечатки он не переносил всю выборку заново.
 */
@Controller
@RequestMapping("/input-control")
@RequiredArgsConstructor
public class InputControlController {

    private final InputControlService inputControlService;
    private final UserRepository userRepository;

    /** Главная страница модуля: сводка и переход к списку партий. */
    @GetMapping({"", "/", "/general", "/general/"})
    public String general(Model model) {

        model.addAttribute("activePage", "general");
        model.addAttribute("batches", inputControlService.findReceivedBatches());
        model.addAttribute("openCards", inputControlService.findOpenCardsByBatch());
        model.addAttribute(
                "completedCards",
                inputControlService.findCompletedCardsByBatch()
        );
        model.addAttribute("inWorkCount", inputControlService.countInWork());
        model.addAttribute("completedCount", inputControlService.countCompleted());

        return "input-control/general";
    }

    /**
     * Список оприходованных партий и кнопка «взять в работу».
     *
     * Завершённые проверки передают отдельной картой: партию после
     * закрытия карточки из списка не убирают, но и работать с ней
     * снова нельзя — поэтому вместо «взять в работу» ей дают ссылку
     * на закрытую карточку.
     */
    @GetMapping({"/control", "/control/"})
    public String index(Model model) {

        model.addAttribute("activePage", "control");
        model.addAttribute("batches", inputControlService.findReceivedBatches());
        model.addAttribute("openCards", inputControlService.findOpenCardsByBatch());
        model.addAttribute(
                "completedCards",
                inputControlService.findCompletedCardsByBatch()
        );

        return "input-control/control/index";
    }

    /**
     * Реестр возвратов поставщику.
     *
     * Список строят по группам брака с решением «возврат»: он служит
     * основой рекламации — по строкам видно, кому, по какому ордеру,
     * какие номера и по какой причине отправляют обратно.
     */
    @GetMapping({"/returns", "/returns/"})
    public String returns(Model model) {

        model.addAttribute("activePage", "returns");
        model.addAttribute("returns", inputControlService.findReturns());

        return "input-control/returns/index";
    }

    /**
     * Берёт партию в работу и открывает её карточку.
     *
     * POST, а не GET: создаётся запись карточки. Повторный вызов для
     * той же партии возвращает уже открытую карточку, поэтому двойной
     * клик по кнопке не заводит вторую проверку.
     */
    @PostMapping("/control/{batchId}/take")
    public String takeInWork(
            @PathVariable Long batchId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            InputControlCard card = inputControlService.takeInWork(
                    batchId,
                    currentUser(authentication)
            );

            return "redirect:/input-control/control/" + card.getId();
        } catch (
                EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/input-control/control";
        }
    }

    /** Карточка проверки партии. */
    @GetMapping("/control/{id}")
    public String card(@PathVariable Long id,
                       Model model,
                       RedirectAttributes redirectAttributes) {

        InputControlService.InputControlCardView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/input-control/control";
        }

        fillCardModel(model, view, formFromView(view));

        return "input-control/control/card";
    }

    /**
     * Сохранение проверки и закрытие карточки.
     *
     * При отказе страница возвращается тем же методом (не redirect'ом)
     * с уже введёнными значениями: перечни номеров длинные, и терять
     * их из-за невыбранной причины — значит заставлять контролёра
     * вводить выборку заново.
     */
    @PostMapping("/control/{id}")
    public String save(
            @PathVariable Long id,
            @Valid @ModelAttribute("inputControlForm") InputControlForm form,
            BindingResult errors,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        InputControlService.InputControlCardView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/input-control/control";
        }

        if (errors.hasErrors()) {
            fillCardModel(model, view, form);
            model.addAttribute(
                    "errorMessage",
                    errors.getAllErrors().get(0).getDefaultMessage()
            );

            return "input-control/control/card";
        }

        InputControlResult result = inputControlService.saveAndComplete(id, form);

        if (!result.completed()) {
            fillCardModel(model, view, form);
            model.addAttribute("errorMessage", result.message());

            return "input-control/control/card";
        }

        redirectAttributes.addFlashAttribute("successMessage", result.message());

        return "redirect:/input-control/control";
    }

    /**
     * Проводит входной контроль по незабракованным ячейкам партии.
     *
     * Кнопка стоит в той же форме, что и завершение проверки, и
     * выбирает действие через {@code formaction}: введённую выборку и
     * группы брака теряют только при отдельной отправке, а здесь
     * они уходят вместе с формой и сохраняются.
     *
     * Карточка остаётся открытой: «провести контроль» отмечает ячейки
     * партии, но итог по партии подводят отдельной кнопкой. Поэтому
     * и возврат идёт на карточку, а не в список партий.
     */
    @PostMapping("/control/{id}/mark")
    public String mark(
            @PathVariable Long id,
            @RequestParam("type") String typeValue,
            @Valid @ModelAttribute("inputControlForm") InputControlForm form,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        InputControlService.InputControlCardView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/input-control/control";
        }

        InputControlType type;

        try {
            type = InputControlType.valueOf(typeValue);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Вид входного контроля не выбран"
            );

            return "redirect:/input-control/control/" + id;
        }

        if (errors.hasErrors()) {
            fillCardModel(model, view, form);
            model.addAttribute(
                    "errorMessage",
                    errors.getAllErrors().get(0).getDefaultMessage()
            );

            return "input-control/control/card";
        }

        InputControlResult result = inputControlService.saveAndMark(
                id,
                form,
                type,
                currentUser(authentication)
        );

        if (!result.completed()) {
            fillCardModel(model, view, form);
            model.addAttribute("errorMessage", result.message());

            return "input-control/control/card";
        }

        redirectAttributes.addFlashAttribute("successMessage", result.message());

        return "redirect:/input-control/control/" + id;
    }

    /** Карточка либо {@code null}: вызывающий уходит в список с flash-ошибкой. */
    private InputControlService.InputControlCardView loadView(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return inputControlService.loadCardView(id);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return null;
        }
    }

    /**
     * Форма поверх сохранённых данных.
     *
     * При повторном открытии карточки поля должны содержать то, что
     * уже внесено: проверку заполняют частями, и пустая форма каждый
     * раз читалась бы как потерянная работа. Группы брака восстанавливают
     * в том же порядке, в котором их заполняли.
     */
    private InputControlForm formFromView(
            InputControlService.InputControlCardView view) {

        InputControlForm form = new InputControlForm();

        form.setBatchId(String.valueOf(view.card().getBatch().getId()));
        form.setSampleCellsText(String.join("\n", view.sampleNumbers()));
        form.setSampleAll(view.card().isSampleAll());
        form.setInputControlType(view.card().getInputControlType() == null
                ? null
                : view.card().getInputControlType().name());
        form.setNote(view.card().getNote());

        for (InputControlService.DefectGroupView saved : view.defectGroups()) {

            InputControlForm.DefectGroupForm group =
                    new InputControlForm.DefectGroupForm();

            group.setDefectCellsText(String.join("\n", saved.numbers()));
            group.setDefectAll(saved.group().isDefectAll());
            group.setDecision(saved.group().getDecision() == null
                    ? null
                    : saved.group().getDecision().name());

            /*
             * В цепочку возвращают только справочные id: текст «Другого»
             * живёт отдельным полем, а его ступень в цепочке остаётся
             * той же, что была выбрана.
             */
            saved.reasons().forEach(chosen -> {
                if (chosen.getReason() != null) {
                    group.getReasonIds().add(chosen.getReason().getId());
                }
                if (chosen.getCustomText() != null) {
                    group.setCustomReasonText(chosen.getCustomText());
                }
            });

            form.getDefectGroups().add(group);
        }

        return form;
    }

    private void fillCardModel(
            Model model,
            InputControlService.InputControlCardView view,
            InputControlForm form) {

        model.addAttribute("activePage", "control");
        model.addAttribute("card", view.card());
        model.addAttribute("batch", view.card().getBatch());
        model.addAttribute("batchNumbers", view.batchNumbers());
        model.addAttribute("sampleNumbers", view.sampleNumbers());

        /*
         * Группы брака отдают списком обёрток: у каждой свои номера,
         * цепочка причин и решение, а ленивые коллекции из шаблона
         * не читаются.
         */
        model.addAttribute("defectGroups", view.defectGroups());

        /*
         * Справочник причин передаётся целиком: дерево плоское и
         * небольшое, поэтому ступени раскрываются на стороне страницы,
         * а не отдельным запросом на каждую выбранную причину.
         */
        model.addAttribute("reasons", inputControlService.findAllReasons());
        model.addAttribute("decisions", InputControlDecision.values());
        model.addAttribute("controlTypes", InputControlType.values());

        /*
         * Строки реестра партии с отметками контроля: карточка рисует
         * по ним, какие ячейки контроль уже прошли и каким видом.
         */
        model.addAttribute("serialMarks", view.serialsByNumber());
        model.addAttribute("inputControlForm", form);
    }

    /**
     * Контролёр карточки: проверку подписывают сотрудником, который её
     * вёл, — по этой строке позже сверяют рекламацию.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись контролёра не найдена"
                ));
    }
}