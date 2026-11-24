package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CustomerForm;
import ru.company.production.entity.Customer;
import ru.company.production.repository.CustomerRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Справочник заказчиков (покупателей) диспетчерского отдела.
 *
 * Заказчик заводится одним наименованием: код вида ЗК-NNN
 * генерируется автоматически, остальные реквизиты справочника
 * остаются пустыми до их позднего заполнения.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerCodeGenerator codeGenerator;

    /**
     * Все заказчики справочника — действующие и архивные.
     */
    @Transactional(readOnly = true)
    public List<Customer> findAll() {
        return customerRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return customerRepository.countByActiveTrue();
    }

    /**
     * Ближайший свободный код заказчика вида ЗК-NNN.
     */
    @Transactional(readOnly = true)
    public String nextCode() {
        return codeGenerator.next(customerRepository.findAllCodes());
    }

    /**
     * Добавляет заказчика по одному наименованию.
     *
     * Наименование уникально в справочнике, поэтому повтор
     * отбивается до записи в таблицу — иначе СУБД отвечает
     * нарушением ограничения uk_customer_name.
     */
    @Transactional
    public Customer create(CustomerForm form) {
        String name = form.getName() == null
                ? ""
                : form.getName().trim();

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите наименование заказчика"
            );
        }

        if (customerRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "Заказчик «" + name + "» уже существует"
            );
        }

        Customer customer = new Customer();

        customer.setName(name);
        customer.setCode(nextFreeCode());
        customer.setActive(true);

        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException exception) {
            /*
             * Гонка двух одноимённых записей: ограничение уникальности
             * переводится в понятное сообщение вместо служебной ошибки.
             */
            throw new IllegalArgumentException(
                    "Заказчик «" + name + "» уже существует",
                    exception
            );
        }
    }

    /**
     * Заказчик по наименованию из внешнего документа — без точного
     * совпадения.
     *
     * В чек-листе Б24 заказчик написан так, как его ввёл диспетчер:
     * «ООО "Новые Технологии"» против «Новые Технологии» в справочнике.
     * Точное сравнение (findByNameIgnoreCase) такого не прощает, а
     * заводить из-за кавычек второго заказчика — значит надвое развести
     * заказы одного клиента.
     *
     * Поэтому сравниваются нормализованные наименования: без кавычек,
     * точек и формы собственности. Совпадением считается и точное
     * равенство, и вхождение: «Новые Технологии» находится и в
     * «ООО "Новые Технологии"», и в «НТ Новые Технологии».
     *
     * Если нормализованное значение подошло нескольким заказчикам,
     * ничего не возвращается: угадать нельзя, и диспетчер выберет
     * покупателя сам.
     *
     * @return найденный заказчик или {@code null}
     */
    @Transactional(readOnly = true)
    public Customer findByNameLoose(String rawName) {

        List<Customer> matches = looseMatches(rawName);

        /*
         * Второе совпадение делает выбор неоднозначным: лучше пустое
         * поле в форме, чем заказ, записанный на чужого клиента.
         */
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * Заказчик по наименованию из чек-листа: найденный или новый.
     *
     * Диспетчер заводит заказ прямо из чек-листа Б24, и заказчика в
     * справочнике может ещё не быть. Заводить его отдельной страницей
     * справочника — лишний труд и риск опечатки, поэтому наименование
     * из листа создаёт запись автоматически (код ЗК-NNN при этом
     * генерируется как обычно).
     *
     * Автоматически создаётся только однозначный новый заказчик. Если
     * нормализованное наименование подошло нескольким существующим,
     * возвращается {@code null}: выбор остаётся за диспетчером, иначе
     * программа записала бы заказ на чужого клиента.
     */
    @Transactional
    public Customer findOrCreateByNameLoose(String rawName) {

        if (rawName == null || rawName.isBlank()) {
            return null;
        }

        List<Customer> matches = looseMatches(rawName);

        if (matches.size() == 1) {
            return matches.get(0);
        }

        if (matches.size() > 1) {
            return null;
        }

        CustomerForm form = new CustomerForm();
        form.setName(rawName.trim());

        return create(form);
    }

    /**
     * Все активные заказчики, чьё нормализованное наименование
     * совпадает с искомым (равенство или вхождение с любой стороны).
     */
    private List<Customer> looseMatches(String rawName) {

        String needle = normalizeName(rawName);

        if (needle.isEmpty()) {
            return List.of();
        }

        List<Customer> matches = new ArrayList<>();

        for (Customer customer : customerRepository.findByActiveTrueOrderByNameAsc()) {

            String candidate = normalizeName(customer.getName());

            if (candidate.isEmpty()) {
                continue;
            }

            if (candidate.equals(needle)
                    || candidate.contains(needle)
                    || needle.contains(candidate)) {
                matches.add(customer);
            }
        }

        return matches;
    }

    /**
     * Наименование для сравнения: без кавычек, точек и формы
     * собственности, в нижнем регистре и с одним пробелом между словами.
     */
    private static String normalizeName(String value) {

        if (value == null) {
            return "";
        }

        String normalized = value
                .replace('\u00A0', ' ')
                .replaceAll("[«»\"'`.,]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        for (String form : LEGAL_FORMS) {
            normalized = normalized.replace(" " + form + " ", " ")
                    .replace(form + " ", " ")
                    .replaceAll("(^| )" + form + "$", "$1");
        }

        return normalized.replaceAll("\\s+", " ").trim();
    }

    /** Формы собственности: они не различают заказчиков. */
    private static final List<String> LEGAL_FORMS =
            List.of("ооо", "зао", "оао", "пао", "ао", "ип", "нко", "оаоз", "гуп", "муп");

    /**
     * Свободный код: расчёт повторяется до первого незанятого значения —
     * часть кодов могла быть заведена вручную и не участвовать в генерации.
     * Занятые значения дописываются в локальную копию списка,
     * чтобы повторный расчёт не предложил то же значение.
     */
    private String nextFreeCode() {
        List<String> codes =
                new ArrayList<>(customerRepository.findAllCodes());

        String code = codeGenerator.next(codes);

        while (codes.contains(code)) {
            codes.add(code);
            code = codeGenerator.next(codes);
        }

        return code;
    }
}
