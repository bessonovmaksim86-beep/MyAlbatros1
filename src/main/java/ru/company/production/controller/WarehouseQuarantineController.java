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
     * Ячейки, переданные бюро, и поле приёмки.
     *
     * Ожидающие номера отдают отдельным списком: страница подсвечивает
     * по ним введённые номера, и кладовщик видит, какие ячейки уже
     * подтверждены, а какие ещё нет.
     */
    @GetMapping({"", "/"})
    public String index(Model model) {
        List<QuarantineTransferService.TransferView> pending =
                transferService.findPending();

        model.addAttribute("activePage", "quarantine-transfer");
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
     * Проведение приёмки: отмеченные складом ячейки принимают на склад,
     * их отметку ставят и в реестре, если номер там есть.
     *
     * Номера сверяют с перечнем передачи (см.
     * {@link QuarantineTransferService#accept}): чужой номер приёмку не
     * пропустит, а неподтверждённые ячейки останутся ждать.
     */
    @PostMapping("/accept")
    public String accept(
            @RequestParam(name = "scanned", required = false)
            List<String> scanned,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        InputControlResult result = transferService.accept(
                scanned,
                currentUser(authentication)
        );

        redirectAttributes.addFlashAttribute(
                result.completed() ? "successMessage" : "errorMessage",
                result.message()
        );

        return "redirect:/warehouse/quarantine-transfer";
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
            @RequestParam(name = "transfer", required = false)
            List<Long> transfers,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        InputControlResult result = transferService.returnToQuarantine(
                transfers,
                currentUser(authentication)
        );

        redirectAttributes.addFlashAttribute(
                result.completed() ? "successMessage" : "errorMessage",
                result.message()
        );

        return "redirect:/warehouse/quarantine-transfer";
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
