(() => {
    'use strict';

    /*
     * Справка о пунктах меню.
     *
     * Смысл — объяснить сотруднику, для чего нужен каждый пункт его
     * интерфейса, не уводя его в документацию: окно открывается из
     * сайдбара, список слева повторяет меню один в один (включая
     * подменю склада и условные пункты, которые видны только этой
     * роли), а справа — информационное поле, в которое и ставится
     * фокус: описание можно сразу читать стрелками и копировать.
     *
     * Список строится из разметки, а не из словаря в скрипте, по той
     * причине, что меню живёт в Thymeleaf-фрагментах: dанные об
     * интерфейсах в двух местах неизбежно разошлись бы, и сотрудник
     * читал бы бы описание раздела, который ему по роли не открыть.
     *
     * Описание пункта берётся из data-help; если его нет — из title.
     * Поэтому пункт, про который забыли написать справку, всё равно
     * не остаётся пустым.
     */

    const ITEM_SELECTORS = [
        '[class*="navigation__item"]',
        '[class*="submenu__item"]'
    ];

    /*
     * Краткая инструкция о проведении и заполнении.
     *
     * Она одна на все интерфейсы: проводить и заполнять везде учат
     * одинаково — создать, запланировать, взять в работу, внести
     * параметры, провести. Разнятся только кнопки последнего шага, и
     * они уже перечислены в описаниях самих разделов, поэтому держать
     * по варианту текста на каждый сайдбар смысла нет.
     *
     * Пункт стоит первыми в списке: сотрудник, открывший справку без
     * понятия о порядке, читает сначала общее, а потом переходит к
     * своему разделу.
     */
    const GENERAL_LABEL = 'Как проводить и заполнять';

    const GENERAL_GUIDE = [
        'Как проводить и заполнять',
        '',
        'Проведение — фиксация факта: система записывает операцию и меняет',
        'состояние заказа, партии или ячейки. До проведения документ',
        'заполняют: пока обязательные поля и позиции состава не внесены,',
        'факт не отпустит.',
        '',
        'Порядок работы с документом:',
        '1) Создать — шапку и состав: изделие, количество, срок, техпроцесс.',
        '2) Запланировать — даты и исполнители: «Планировать заказ», затем',
        '   «Внести план в заказ».',
        '3) Взять в работу — из «Мои операции» либо через «Распределение',
        '   работ».',
        '4) Заполнить контролируемые параметры: число — в заданных границах,',
        '   текст — по образцу, ячеечный — из состава входящих ячеек.',
        '5) Провести — «Провести операцию» (или «Провести все операции»),',
        '   «Сохранить и завершить проверку», «Принять подтверждённые на',
        '   склад», «Проверить партию и оприходовать».',
        '',
        'Проведение не отпустит, если:',
        '— обязательный параметр или позиция состава не заполнены;',
        '— у сотрудника нет привязки к рабочему месту этой операции;',
        '— на нужный день поставлено отсутствие или праздник;',
        '— код или наименование в справочнике уже занят.',
        '',
        'Проведённый факт задним числом не переписывают: его возвращают',
        '(«Вернуть в работу», «Отправить на согласование», «Вернуть в',
        'изолятор брака») и проводят заново. Строку справочника удаляют,',
        'пока она не вошла в заказ или техпроцесс.',
        '',
        'Цифра у пункта меню — дела, которые ждут вашего действия.'
    ].join('\n');

    const TEXT_SELECTORS = [
        '[class*="navigation__text"]',
        '[class*="submenu__text"]'
    ];

    const ICON_PATH =
        'M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 ' +
        '8-10.91V5l-8-3Zm1 15h-2v-2h2v2Zm0-4h-2V9h2v4Z';

    function findSidebar(button) {
        return button.closest('.sidebar') || document;
    }

    function textOf(scope, selectors) {
        for (const selector of selectors) {
            const node = scope.querySelector(selector);

            if (node && node.textContent.trim()) {
                return node.textContent.trim();
            }
        }

        return '';
    }

    function isSubmenuEntry(anchor) {
        return anchor.matches('[class*="submenu__item"]');
    }

    /*
     * Пункт меню — это <a>, а не <button>: ссылка ведёт в раздел, и
     * справка не должна ломать эту привычку. Кнопки («Выйти», «Справка»)
     * под селектор не попадают, поэтому в список они не лезут.
     *
     * Ищем по всей панели, а не только внутри <nav>: в меню технолога
     * «Обзор отдела» стоит над nav, и такой пункт выпал бы из справки.
     */
    function collectEntries(sidebar) {
        return Array.from(sidebar.querySelectorAll(ITEM_SELECTORS.join(', ')))
            .map((anchor) => {
                const label = textOf(anchor, TEXT_SELECTORS) ||
                    (anchor.getAttribute('title') || '').trim();

                if (!label) {
                    return null;
                }

                return {
                    label: label,
                    description: (anchor.getAttribute('data-help') ||
                        anchor.getAttribute('title') || '').trim(),
                    href: anchor.getAttribute('href') || '',
                    active: anchor.classList.contains('is-active'),
                    sub: isSubmenuEntry(anchor)
                };
            })
            .filter(Boolean);
    }

    function buildDialog() {
        const root = document.createElement('div');

        root.className = 'app-help';
        root.innerHTML =
            '<div class="app-help__overlay" data-help-overlay></div>' +
            '<div class="app-help__dialog" role="dialog" aria-modal="true" ' +
            'aria-labelledby="appHelpTitle">' +
            '  <div class="app-help__head">' +
            '    <div>' +
            '      <h2 class="app-help__title" id="appHelpTitle">Справка о разделах</h2>' +
            '      <span class="app-help__subtitle" data-help-count></span>' +
            '    </div>' +
            '    <button type="button" class="app-help__close" data-help-close ' +
            '            title="Закрыть справку (Esc)">&#10005;</button>' +
            '  </div>' +
            '  <div class="app-help__list" role="listbox" aria-label="Разделы меню" ' +
            '       data-help-list></div>' +
            '  <div class="app-help__body">' +
            '    <p class="app-help__field-label" data-help-label>Раздел</p>' +
            '    <textarea class="app-help__text" data-help-field rows="8" readonly ' +
            '              aria-label="Описание раздела"></textarea>' +
            '    <p class="app-help__meta">Стрелки вверх и вниз — по разделам, ' +
            'один край списка — общая инструкция «Как проводить и заполнять», ' +
            'Esc — закрыть.</p>' +
            '    <a class="app-help__open" data-help-open-section href="#">' +
            '        Перейти в раздел</a>' +
            '  </div>' +
            '</div>';

        document.body.appendChild(root);

        return root;
    }

    function state() {
        return {
            root: null,
            list: null,
            field: null,
            label: null,
            count: null,
            link: null,
            entries: [],
            index: -1,
            opener: null
        };
    }

    const app = state();

    function ensureDialog() {
        if (app.root) {
            return;
        }

        app.root = buildDialog();
        app.list = app.root.querySelector('[data-help-list]');
        app.field = app.root.querySelector('[data-help-field]');
        app.label = app.root.querySelector('[data-help-label]');
        app.count = app.root.querySelector('[data-help-count]');
        app.link = app.root.querySelector('[data-help-open-section]');

        app.root.addEventListener('click', (event) => {
            if (event.target.hasAttribute('data-help-overlay') ||
                event.target.closest('[data-help-close]')) {
                close();
            }
        });

        /*
         * Поле только для чтения, поэтому выбор текста мышью работает, а
         * набор символов нет. Курсор в конце строки при клике не нужен —
         * описание читают целиком.
         */
        app.field.addEventListener('keydown', (event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                select(app.index + (event.key === 'ArrowDown' ? 1 : -1));
            }
        });

        app.list.addEventListener('click', (event) => {
            const item = event.target.closest('[data-help-index]');

            if (item) {
                select(Number(item.getAttribute('data-help-index')));
            }
        });

        document.addEventListener('keydown', (event) => {
            if (!app.root.classList.contains('is-open')) {
                return;
            }

            if (event.key === 'Escape') {
                event.preventDefault();
                close();
            } else if (event.key === 'Tab') {
                trapFocus(event);
            }
        });
    }

    function trapFocus(event) {
        const focusable = Array.from(
            app.root.querySelectorAll('button, [href], textarea')
        ).filter((node) => node.offsetParent !== null);

        if (!focusable.length) {
            return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    /*
     * Список начинается с общей инструкции: сотрудник, открывший
     * справку без понятия о порядке, читает сначала её, а потом
     * переходит к своему разделу.
     *
     * Инструкция живёт под отдельным индексом, а не как элемент
     * entries: пунктов меню она не заменяет и в счётчик «Пунктов в
     * меню» не входит, иначе цифра перестала бы совпадать с меню.
     */
    const GUIDE_INDEX = -1;

    function renderList() {
        app.list.innerHTML =
            '<p class="app-help__list-title">Работа с системой</p>' +
            '<button type="button" role="option" class="app-help__item app-help__item--guide"' +
            ' data-help-index="' + GUIDE_INDEX + '">' +
            escapeHtml(GENERAL_LABEL) +
            '</button>' +
            '<p class="app-help__list-title">Разделы этого интерфейса</p>' +
            app.entries
                .map((entry, position) =>
                    '<button type="button" role="option" class="app-help__item' +
                    (entry.sub ? ' app-help__item--sub' : '') +
                    '" data-help-index="' + position + '">' +
                    escapeHtml(entry.label) +
                    '</button>')
                .join('');
    }

    function escapeHtml(value) {
        return String(value).replace(/[&<>"]/g, (symbol) => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;'
        }[symbol]));
    }

    /*
     * Отметка выбранного идёт по data-help-index, а не по порядковому
     * месту среди детей списка: с заголовками групп позиция узла
     * перестала бы совпадать с индексом записи.
     */
    function markSelected(index) {
        Array.from(app.list.querySelectorAll('[data-help-index]'))
            .forEach((node) => {
                const selected =
                    Number(node.getAttribute('data-help-index')) === index;

                node.classList.toggle('is-selected', selected);
                node.setAttribute('aria-selected', selected ? 'true' : 'false');
            });
    }

    function select(position) {
        if (!app.entries.length) {
            return;
        }

        /*
         * Общая инструкция замкнута с меню в одно кольцо: стрелка вверх
         * с первого раздела уходит в неё, стрелка вниз от неё — в
         * первый раздел. Так инструкция остаётся достижимой и при
         * листании, а не только при открытии окна.
         */
        const last = app.entries.length - 1;
        let index = position;

        if (index < GUIDE_INDEX) {
            index = last;
        } else if (index > last) {
            index = GUIDE_INDEX;
        }

        const guide = index === GUIDE_INDEX;
        const entry = guide ? null : app.entries[index];

        app.index = index;

        markSelected(index);

        /*
         * Заголовок инструкции есть в самом тексте, поэтому подпись поля
         * короткая: иначе одна и та же строка стояла бы друг под другом.
         */
        app.label.textContent = guide
            ? 'Общая инструкция'
            : (entry.sub ? 'Подраздел: ' : 'Раздел: ') + entry.label;
        app.field.value = guide
            ? GENERAL_GUIDE
            : (entry.description || 'Описание раздела ещё не заполнено.');

        /*
         * Фокус уходит в поле, а остаётся в списке: сотрудник открывает
         * справку, чтобы прочитать текст, и дальше листает его стрелками.
         */
        app.field.focus();
        app.field.setSelectionRange(0, 0);

        const item = app.list.querySelector('[data-help-index="' + index + '"]');

        if (item) {
            item.scrollIntoView({ block: 'nearest' });
        }

        if (entry && entry.href && entry.href !== '#') {
            app.link.href = entry.href;
            app.link.hidden = false;
        } else {
            app.link.hidden = true;
        }
    }

    function close() {
        app.root.classList.remove('is-open');
        app.root.setAttribute('aria-hidden', 'true');

        if (app.opener) {
            app.opener.focus();
        }
    }

    function open(button) {
        ensureDialog();

        app.entries = collectEntries(findSidebar(button));
        app.opener = button;

        if (!app.entries.length) {
            return;
        }

        renderList();

        const current = app.entries.findIndex((entry) => entry.active);

        app.root.classList.add('is-open');
        app.root.setAttribute('aria-hidden', 'false');
        app.count.textContent = 'Пунктов в меню: ' + app.entries.length;

        /*
         * Активный раздел остаётся точкой входа: справка отвечает на
         * вопрос «что это за пункт», и переучивать привычку ради
         * общего текста не стоило. Инструкция стоит первым пунктом
         * списка и открывается стрелкой вверх от первого раздела.
         */
        select(current < 0 ? GUIDE_INDEX : current);
    }

    function init(root) {
        const scope = root || document;

        scope.querySelectorAll('[data-app-help]').forEach((button) => {
            if (button.dataset.appHelpBound === 'true') {
                return;
            }

            button.dataset.appHelpBound = 'true';
            button.addEventListener('click', () => open(button));
        });
    }

    function markButtonIcon() {
        /*
         * Значок рисуется скриптом, а не копипастится в семь фрагментов:
         * в разметке остаётся одна строка, и иконка не разъезжается по
         * разделам.
         */
        document.querySelectorAll('[data-app-help] .app-help-button__icon')
            .forEach((host) => {
                if (host.firstChild) {
                    return;
                }

                host.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
                    '<path d="' + ICON_PATH + '"/></svg>';
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            markButtonIcon();
            init();
        });
    } else {
        markButtonIcon();
        init();
    }

    window.appHelp = { init: init, open: open };
})();
