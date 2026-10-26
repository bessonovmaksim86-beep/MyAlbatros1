package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.QuarantineTransferService;

import java.util.List;

/**
 * Приёмка на склад полуфабрикатов ячеек, перемещённых из изолятора
 * брака.
 *
 * Раздел стоит рядом с «Приёмкой с БВК»: жест тот же — принять то, что
 * приехало из бюро входного контроля, но объектом выступает ячейка,
 * ранее числившаяся браком. Бюро выносит её из изолятора с указанием
 * причины, а склад сверяет номера с тем, что реально привезли, и
 * проводит приёмку.
 *
 * V75: приёмка разделена на два раздела. Подсборки — чекбокс
 * «отметить как готовые ячейки» по умолчанию снят, позиция ложится
 * на склад, но остаётся подсборкой. Ячейки — чекбокс не предлагается:
 * ячейка подсборкой стать не может, и готовность восстанавливается
 * автоматически.
 *
 * Проверку номеров держат отдельным полем, а не одной кнопкой:
 * перемещение — ещё не приёмка. Ячейку могли переместить списком, а
 * привезли не всё, и кладовщик обязан отметить только то, что лежит
 * перед ним. Неподтверждённые номера остаются ждать приёмки, а не
 * принимаются «заодно» со всем перечнем.
 *
 * Проведение идёт redirect'ом: приёмку проводят один раз, и повторный
 * F5 не должен отметить ячейки второй раз.
 */
@Controller
@RequestMapping("/warehouse/quarantine-transfer")
@RequiredArgsConstructor
public class WarehouseQuarantineController {

    private final QuarantineTransferService transferService;
    private final UserRepository userRepository;

    /**
     * Подсборки, переданные бюро, и поле приёмки.
     *
     * Ожидающие номера отдают отдельным списком: страница подсвечивает
     * по ним введённые номера, и кладовщик видит, какие ячейки уже
     * подтверждены, а какие ещё нет.
     *
     * Границу задаёт сам адрес, а не параметр: раздел «Ячейки» —
     * отдельный путь, и запросить чужой состав нельзя, потому что
     * адрес выбирает и перечень, и признак готовности.
     */
    @GetMapping({"", "/"})
    public String indexSubassemblies(Model model) {
        return section(QuarantineTransferService.Section.SUBASSEMBLY, model);
    }

    /**
     * Ячейки, переданные бюро.
     *
     * Раздел отличается от «Подборок» отсутствием чекбокса готовности:
     * ячейка подсборкой стать не может, а её готовность снимает
     * попадание в изолятор — приёмка складом её возвращает.
     */
    @GetMapping("/cells")
    public String indexCells(Model model) {
        return section(QuarantineTransferService.Section.CELL, model);
    }

    /**
     * Разметка раздела: свой состав, его номера и ключ активного пункта.
     *
     * Оба раздела рисуются одним шаблоном — разметка у них общая, и
     * различаются только состав и наличие чекбокса, который шаблон
     * выводит по признаку {@code section.cellReadyForced}.
     */
    private String section(QuarantineTransferService.Section section,
                           Model model) {

        List<QuarantineTransferService.TransferView> pending =
                transferService.findPending(section);

        model.addAttribute("activePage", section.getActivePage());
        model.addAttribute("section", section);
        model.addAttribute("transfers", pending);
        model.addAttribute(
                "cellNumbers",
                pending.stream()
                        .map(QuarantineTransferService.TransferView::getFullNumber)
                        .distinct()
                        .toList()
        );

        return "warehouse/quarantine-transfer/index";
    }

    /**
     * Проведение приёмки: отмеченные складом ячейки принимают на склад.
     *
     * V75: раздел «Подсборки» принимает позиции подсборками, а чекбокс
     * «отметить как готовые ячейки» переводит их в ячейки — без него
     * {@code cell_ready_at} остаётся пустым, и позиция не выходит в
     * комплектование. Раздел «Ячейки» чекбокса не предлагает: ячейка
     * подсборкой стать не может, и готовность восстанавливается
     * автоматически.
     *
     * Проведение идёт redirect'ом: приёмку проводят один раз, и повторный
     * F5 не должен отметить ячейки второй раз. При отказе страница
     * возвращается тем же методом с уже введёнными номерами.
     */
    @PostMapping("/accept")
    public String accept(
            @RequestParam(name = "section", required = false) String sectionParam,
            @RequestParam(name = "scanned", required = false)
            List<String> scanned,
            @RequestParam(name = "cellReady", required = false)
            String cellReady,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        QuarantineTransferService.Section section =
                QuarantineTransferService.Section.ofNullable(sectionParam);

        boolean cellReadyFlag = section.isCellReadyForced()
                || "true".equalsIgnoreCase(cellReady);

        InputControlResult result = transferService.accept(
                section,
                scanned,
                cellReadyFlag,
                currentUser(authentication)
        );

        redirectAttributes.addFlashAttribute(
                result.completed() ? "successMessage" : "errorMessage",
                result.message()
        );

        return "redirect:" + section.path();
    }

    /**
     * Возврат ячеек в изолятор брака.
     *
     * Строку перемещения снимают, и перечень изолятора показывает
     * ячейку снова: разбирательство по браку не окончено, а держать
     * её «в пути» на склад бессмысленно.
     */
    @PostMapping("/return")
    public String returnToQuarantine(
            @RequestParam(name = "section", required = false) String sectionParam,
            @RequestParam(name = "transfer", required = false)
            List<Long> transfers,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        QuarantineTransferService.Section section =
                QuarantineTransferService.Section.ofNullable(sectionParam);

        InputControlResult result = transferService.returnToQuarantine(
                transfers,
                currentUser(authentication)
        );

        redirectAttributes.addFlashAttribute(
                result.completed() ? "successMessage" : "errorMessage",
                result.message()
        );

        return "redirect:" + section.path();
    }

    /**
     * Кладовщик приёмки: ячейки принимают сотрудником, который провёл
     * приёмку, — по этой строке позже сверяют остаток на складе.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));
    }
}
