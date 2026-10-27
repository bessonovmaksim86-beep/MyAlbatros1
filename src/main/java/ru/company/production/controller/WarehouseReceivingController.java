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
import ru.company.production.dto.CellReceivingForm;
import ru.company.production.dto.CellScanningResult;
import ru.company.production.dto.LabelPrintResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.CellReceivingService;
import ru.company.production.service.LabelPrintService;
import ru.company.production.service.WarehouseSupplierService;

import java.util.List;

/**
 * Оприходование ячеек: приход → номера → печать QR → подтверждение →
 * сканирование.
 *
 * Каждый шаг живёт отдельной страницей и заканчивается redirect'ом:
 * повторный F5 на шаге генерации не должен выпускать вторую партию
 * номеров, а на шаге сканирования — вносить ячейки в реестр дважды.
 *
 * Правила проверки партии целиком находятся в
 * {@link CellReceivingService}; контроллер лишь превращает результат
 * проверки в сообщения и возвращает оператору уже введённые строки,
 * чтобы ему не пришлось пересканировать партию заново из-за одного
 * лишнего номера.
 */
@Controller
@RequestMapping("/warehouse/receiving")
@RequiredArgsConstructor
public class WarehouseReceivingController {

    private final CellReceivingService receivingService;
    private final WarehouseSupplierService supplierService;
    private final UserRepository userRepository;
    private final LabelPrintService labelPrintService;

    /** Форма прихода и журнал партий. */
    @GetMapping({"", "/"})
    public String index(
            @ModelAttribute("receivingForm") CellReceivingForm receivingForm,
            Model model
    ) {
        fillIndexModel(model);

        return "warehouse/receiving/index";
    }

    /**
     * Оформление прихода: под него сразу резервируется диапазон
     * номеров, и оператор переходит к печати.
     */
    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("receivingForm") CellReceivingForm receivingForm,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        if (!errors.hasErrors()) {
            try {
                CellReceivingBatch batch = receivingService.create(
                        receivingForm,
                        currentUser(authentication)
                );

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Партия сформирована: номера "
                                + batch.fullNumber(batch.getFirstSerial())
                                + " … "
                                + batch.fullNumber(batch.getLastSerial())
                                + " (" + batch.getQuantity() + " шт.)"
                );
                redirectAttributes.addFlashAttribute(
                        "receivingForm",
                        new CellReceivingForm()
                );

                return "redirect:/warehouse/receiving/"
                        + batch.getId() + "/qr";
            } catch (
                    IllegalArgumentException
                    | IllegalStateException exception
            ) {
                errors.reject("receiving.create", exception.getMessage());
            }
        }

        fillIndexModel(model);

        return "warehouse/receiving/index";
    }

    /** Шаг печати: перечень номеров и передача их в программу этикеток. */
    @GetMapping("/{id}/qr")
    public String qrPage(@PathVariable Long id,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        CellReceivingBatch batch = getBatch(id, redirectAttributes);

        if (batch == null) {
            return "redirect:/warehouse/receiving";
        }

        model.addAttribute("activePage", "receiving");
        model.addAttribute("batch", batch);
        model.addAttribute("numbers", batch.fullNumbers());
        model.addAttribute("step", "qr");

        /*
         * Состояние печати приходит с сервера, а не живёт текстом в
         * разметке: оператор должен видеть, настроено ли это рабочее
         * место, до нажатия кнопки, а не получать ошибку после.
         */
        String problem = labelPrintService.configurationProblem();

        model.addAttribute("printReady", problem == null);
        model.addAttribute("printProblem", problem);

        return "warehouse/receiving/qr";
    }

    /**
     * Передаёт номера партии в BarTender.
     *
     * Печать и переход по шагам разнесены намеренно: задание ушло на
     * принтер ещё не значит, что кладовщик проверил вышедший лист.
     * Поэтому после успешной отправки страница остаётся на месте с
     * числом этикеток, а дальше оператор идёт сам.
     *
     * Партия помечается напечатанной только при коде возврата 0 —
     * иначе она ушла бы на сканирование по этикеткам, которых нет.
     */
    @PostMapping("/{id}/qr/print")
    public String printLabels(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        CellReceivingBatch batch;

        try {
            batch = receivingService.get(id);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/warehouse/receiving";
        }

        LabelPrintResult result = labelPrintService.printBatch(batch);

        if (!result.printed()) {
            redirectAttributes.addFlashAttribute("errorMessage", result.message());

            return "redirect:/warehouse/receiving/" + id + "/qr";
        }

        receivingService.markQrPrinted(id);

        redirectAttributes.addFlashAttribute("successMessage", result.message());

        return "redirect:/warehouse/receiving/" + id + "/qr";
    }

    /**
     * Отмечает печать и переводит к подтверждению.
     *
     * Шаг обязателен: без него нельзя отличить партию, по которой
     * этикетки ещё не напечатаны, от партии, готовой к сканированию.
     * Когда BarTender не настроен, это единственный способ закрыть
     * шаг — номера передаются в него вручную, и оператор подтверждает
     * это сам.
     */
    @PostMapping("/{id}/qr")
    public String markPrinted(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            receivingService.markQrPrinted(id);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/warehouse/receiving";
        }

        return "redirect:/warehouse/receiving/" + id + "/confirm";
    }

    /**
     * Сводка прихода перед окончательным оприходованием.
     *
     * Экран обязателен: после подтверждения номера попадают в реестр,
     * и исправить реквизиты задним числом нельзя. Поэтому здесь
     * перечислены все данные, которые оператор вводил сам.
     */
    @GetMapping("/{id}/confirm")
    public String confirmPage(@PathVariable Long id,
                              Model model,
                              RedirectAttributes redirectAttributes) {

        CellReceivingBatch batch = getBatch(id, redirectAttributes);

        if (batch == null) {
            return "redirect:/warehouse/receiving";
        }

        model.addAttribute("activePage", "receiving");
        model.addAttribute("batch", batch);
        model.addAttribute("numbers", batch.fullNumbers());
        model.addAttribute("step", "confirm");

        return "warehouse/receiving/confirm";
    }

    /** Поле сканирования: ячейки партии вводятся по одной. */
    @GetMapping("/{id}/scan")
    public String scanPage(@PathVariable Long id,
                           Model model,
                           RedirectAttributes redirectAttributes) {

        CellReceivingBatch batch = getBatch(id, redirectAttributes);

        if (batch == null) {
            return "redirect:/warehouse/receiving";
        }

        model.addAttribute("activePage", "receiving");
        model.addAttribute("batch", batch);
        model.addAttribute("numbers", batch.fullNumbers());
        model.addAttribute("step", "scan");

        return "warehouse/receiving/scan";
    }

    /**
     * Проверка отсканированной партии.
     *
     * Пока партия не совпала целиком, в реестр не записывается ни одна
     * ячейка — иначе расхождение реестра с фактом осталось бы
     * незамеченным. Строки сканирования возвращаются в форму, чтобы
     * оператор дополнял ввод, а не начинал заново.
     */
    @PostMapping("/{id}/scan")
    public String scan(
            @PathVariable Long id,
            @RequestParam(name = "scanned", required = false)
            List<String> scanned,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        CellScanningResult result = scanBatch(id, scanned, redirectAttributes);

        if (result == null) {
            return "redirect:/warehouse/receiving";
        }

        if (result.accepted()) {
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Партия оприходована: "
                            + (scanned == null ? 0 : scanned.size())
                            + " ячеек внесено в реестр"
            );

            return "redirect:/warehouse/receiving";
        }

        model.addAttribute("activePage", "receiving");
        model.addAttribute(
                "batch",
                receivingService.get(id)
        );
        model.addAttribute(
                "numbers",
                receivingService.get(id).fullNumbers()
        );
        model.addAttribute("step", "scan");
        model.addAttribute("scannedLines", scanned);
        model.addAttribute("result", result);
        model.addAttribute("errorMessage", result.message());

        return "warehouse/receiving/scan";
    }

    /**
     * Результат проверки либо {@code null}, если партия не найдена:
     * в этом случае вызывающий уходит в список с flash-сообщением.
     */
    private CellScanningResult scanBatch(
            Long id,
            List<String> scanned,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return receivingService.confirm(id, scanned);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return null;
        }
    }

    private void fillIndexModel(Model model) {
        model.addAttribute("activePage", "receiving");

        model.addAttribute("batches", receivingService.findAll());
        model.addAttribute("classifiers", receivingService.findCellClassifiers());

        /*
         * Поставщик выбирается только из справочника: свободный ввод
         * возвращает ту самую проблему, из-за которой справочник и
         * заведён — одно и то же наименование в нескольких написаниях
         * раскалывает сводку по поставщику.
         */
        model.addAttribute(
                "suppliers",
                supplierService.findAll()
        );
    }

    private CellReceivingBatch getBatch(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return receivingService.get(id);
        } catch (EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return null;
        }
    }

    /*
     * Автор прихода нужен для истории: приход склада — операция,
     * которую позже сверяют с конкретным сотрудником.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));
    }
}
