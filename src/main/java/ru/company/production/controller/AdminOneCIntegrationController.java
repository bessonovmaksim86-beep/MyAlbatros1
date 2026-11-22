package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.repository.ZpExportView;
import ru.company.production.service.OneCIntegrationService;

import java.time.LocalDate;
import java.util.List;

/**
 * Раздел «Интеграция с 1С» в модуле администрирования.
 *
 * Первая операция — выборка заказов на производство (ЗП) за период по
 * признаку «закрытые / оформленные». Интерфейс готов к обмену: когда
 * станет известен способ подключения 1С, рядом с выборкой появится
 * реальная отправка вместо текущей заглушки в
 * {@link OneCIntegrationService#sendTo1C}.
 *
 * Выборка идёт GET-формой: период и режим — это фильтр, который
 * администратор вправе повторить кнопкой «назад» или ссылкой с
 * параметрами, как в разделе распределения работ цеха.
 */
@Controller
@RequestMapping("/admin/1c")
@RequiredArgsConstructor
public class AdminOneCIntegrationController {

    private final OneCIntegrationService oneCService;

    /**
     * Форма выборки и таблица результатов.
     *
     * Список строк заполняется только когда задан хотя бы один край
     * периода: пустой экран при первом входе не должен выглядеть как
     * «заказов нет» — иначе администратор решит, что база пуста.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "periodFrom", required = false) String periodFrom,
            @RequestParam(name = "periodTo", required = false) String periodTo,
            @RequestParam(name = "mode", required = false) String mode,
            Model model
    ) {
        model.addAttribute("activePage", "oneCIntegration");

        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);
        String selectedMode = normalizeMode(mode);

        model.addAttribute("periodFrom", periodFrom == null ? "" : periodFrom);
        model.addAttribute("periodTo", periodTo == null ? "" : periodTo);
        model.addAttribute("mode", selectedMode);
        model.addAttribute("closedMode", OneCIntegrationService.MODE_CLOSED);
        model.addAttribute("issuedMode", OneCIntegrationService.MODE_ISSUED);

        boolean requested = !isBlank(periodFrom) || !isBlank(periodTo);

        if (requested) {
            List<ZpExportView> rows = oneCService.loadZp(from, to, selectedMode);

            model.addAttribute("rows", rows);
            model.addAttribute("rowCount", rows.size());
        }

        return "admin/1c-integration";
    }

    /**
     * Пробная отправка выбранной выборки в 1С.
     *
     * Сейчас вызывает заглушку: протокол обмена ещё не выбран. Форма
     * POST, потому что операция меняет состояние внешней системы и её
     * нельзя повторять кнопкой «назад» или ссылкой из письма.
     */
    @PostMapping("/send")
    public String send(
            @RequestParam(name = "periodFrom", required = false) String periodFrom,
            @RequestParam(name = "periodTo", required = false) String periodTo,
            @RequestParam(name = "mode", required = false) String mode,
            RedirectAttributes redirectAttributes
    ) {
        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);

        if (from == null && to == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Укажите хотя бы одну границу периода"
            );

            return "redirect:/admin/1c";
        }

        String selectedMode = normalizeMode(mode);
        List<ZpExportView> rows = oneCService.loadZp(from, to, selectedMode);

        boolean sent = oneCService.sendTo1C(rows);

        redirectAttributes.addFlashAttribute(
                sent ? "successMessage" : "errorMessage",
                sent
                        ? "Отправка в 1С выполнена (записей: " + rows.size()
                                + "). Реальный обмен включим после выбора протокола."
                        : "1С не приняла данные: проверьте параметры подключения."
        );

        return redirectWithPeriod(from, to, selectedMode);
    }

    /**
     * Возврат на страницу с теми же параметрами выборки: после POST
     * администратор обязан увидеть тот же список, который отправлял.
     */
    private static String redirectWithPeriod(LocalDate from, LocalDate to, String mode) {
        StringBuilder target = new StringBuilder("redirect:/admin/1c?mode=").append(mode);

        if (from != null) {
            target.append("&periodFrom=").append(from);
        }

        if (to != null) {
            target.append("&periodTo=").append(to);
        }

        return target.toString();
    }

    /** Дата из поля {@code type="date"}: пусто или сбой формата — {@code null}. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    /** Режим: закрытые либо оформленные; любое иное значение — оформленные. */
    private static String normalizeMode(String mode) {
        return OneCIntegrationService.MODE_CLOSED.equalsIgnoreCase(
                mode == null ? "" : mode.trim()
        )
                ? OneCIntegrationService.MODE_CLOSED
                : OneCIntegrationService.MODE_ISSUED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
