(() => {
    'use strict';

    /*
     * Карточка входного контроля.
     *
     * Всё, что здесь происходит, — отправка формы в том виде, в каком
     * её видит контролёр. Сервис сверяет номера с партией и требует
     * причину с решением для каждой группы брака, но отказ после
     * длинной выборки читается плохо: подсветка чужих номеров и
     * номеров вне выборки показывает проблему до отправки, а не после.
     *
     * Группы брака живут в #bvkDefectGroups. Их индексы в именах полей
     * (defectGroups[N].*) обязан держать Spring: список связывается по
     * индексу, и разрыв нумерации означал бы пустую группу в середине.
     * Поэтому после добавления или удаления группы индексы переставляет
     * функция reindex().
     */

    const FORM = document.getElementById('bvkControlForm');

    if (!FORM) {
        return;
    }

    const SAMPLE_ALL = document.getElementById('bvkSampleAll');
    const SAMPLE_TEXT = document.getElementById('bvkSampleCellsText');
    const SAMPLE_COUNTER = document.getElementById('bvkSampleCounter');
    const SAMPLE_UNKNOWN = document.getElementById('bvkSampleUnknown');
    const SAMPLE_UNKNOWN_LIST = document.getElementById('bvkSampleUnknownList');
    const BATCH_NUMBERS = document.getElementById('bvkBatchNumbers');

    const GROUPS = document.getElementById('bvkDefectGroups');
    const GROUP_TEMPLATE = document.getElementById('bvkGroupTemplate');
    const ADD_GROUP = document.getElementById('bvkAddGroup');
    const TOTAL_COUNTER = document.getElementById('bvkDefectCounter');

    const REASON_SOURCE = document.getElementById('bvkReasonSource');

    /** Маркер индекса в чистом экземпляре группы. */
    const PLACEHOLDER = '@IDX@';

    const batchNumbers = splitNumbers(FORM.getAttribute('data-batch-numbers'));

    /* Справочник причин: строки скрытого списка → карта и корни. */
    const reasons = new Map();
    const roots = [];

    REASON_SOURCE.querySelectorAll('li').forEach((row) => {
        const reason = {
            id: row.getAttribute('data-id'),
            parentId: row.getAttribute('data-parent') || null,
            custom: row.getAttribute('data-custom') === 'true',
            name: row.getAttribute('data-name')
        };

        reasons.set(reason.id, reason);

        if (!reason.parentId) {
            roots.push(reason);
        }
    });

    function splitNumbers(value) {
        if (!value) {
            return [];
        }

        return value
            .split(',')
            .map((item) => item.trim())
            .filter((item) => item.length > 0);
    }

    /* Номера из поля ввода: те же разделители, что разбирает сервис. */
    function readNumbers(textarea) {
        if (!textarea || !textarea.value) {
            return [];
        }

        const seen = new Set();

        textarea.value
            .split(/[,;\r\n]+/)
            .forEach((line) => {
                const digits = line.replace(/\D/g, '');

                /* Полный номер ячейки: 4 цифры кода + 5 цифры серии. */
                if (digits.length === 9) {
                    seen.add(digits);
                }
            });

        return Array.from(seen);
    }

    function byName(first, second) {
        return first.name.localeCompare(second.name, 'ru');
    }

    function childrenOf(id) {
        return Array.from(reasons.values())
            .filter((reason) => reason.parentId === id)
            .sort(byName);
    }

    function groups() {
        return Array.from(GROUPS.querySelectorAll(':scope > .bvk-group'));
    }

    /** Поля группы по её текущему индексу. */
    function parts(group) {
        const index = group.getAttribute('data-index');

        return {
            index: index,
            all: document.getElementById('bvkGroupAll_' + index),
            text: document.getElementById('bvkGroupCells_' + index),
            counter: document.getElementById('bvkGroupCounter_' + index),
            unknown: document.getElementById('bvkGroupUnknown_' + index),
            stack: document.getElementById('bvkGroupReasons_' + index),
            custom: document.getElementById('bvkGroupCustom_' + index),
            customText: document.getElementById('bvkGroupCustomText_' + index)
        };
    }


    /* =========================================================
       ПЕРЕИНДЕКСАЦИЯ
       ========================================================= */

    /*
     * Имена и id полей группы содержат её порядковый номер, поэтому
     * при удалении группы из середины нумерация рвётся, и Spring
     * собрал бы список с пустым элементом. Меняют только индексы в
     * именах и суффиксы id: data-chosen хранит идентификаторы причин,
     * и подмена цифр в нём превратила бы причину в несуществующую.
     */
    function reindex() {
        groups().forEach((group, position) => {
            const previous = group.getAttribute('data-index');

            if (previous !== String(position)) {

                [group].concat(Array.from(
                    group.querySelectorAll('[name], [id], [for]')
                )).forEach((element) => {
                    ['name', 'id', 'for'].forEach((attribute) => {
                        const value = element.getAttribute(attribute);

                        if (!value) {
                            return;
                        }

                        element.setAttribute(
                            attribute,
                            value
                                .split('defectGroups[' + previous + ']')
                                .join('defectGroups[' + position + ']')
                                .replace(
                                    new RegExp('_' + previous + '$'),
                                    '_' + position
                                )
                        );
                    });
                });
            }

            group.setAttribute('data-index', String(position));

            const title = group.querySelector('.bvk-group__title');

            if (title) {
                title.textContent = 'Группа брака ' + (position + 1);
            }
        });
    }


    /* =========================================================
       СПИСКИ НОМЕРОВ: СЧЁТЧИКИ, ПОДСВЕТКА, «ВСЕ ЯЧЕЙКИ»
       ========================================================= */

    /**
     * Подсветка номеров партии.
     *
     * «is-defect» — номер в любой группе брака.
     */
    function markList(sampleNumbers, defectNumbers) {
        if (!BATCH_NUMBERS) {
            return;
        }

        BATCH_NUMBERS.querySelectorAll('li').forEach((item) => {
            const number = item.getAttribute('data-number');

            item.classList.toggle('is-sampled', sampleNumbers.includes(number));
            item.classList.toggle('is-defect', defectNumbers.includes(number));
        });
    }

    function renderSample() {
        const numbers = readNumbers(SAMPLE_TEXT);
        const foreign = numbers.filter((number) => !batchNumbers.includes(number));

        SAMPLE_COUNTER.textContent = SAMPLE_ALL.checked
            ? batchNumbers.length + ' из ' + batchNumbers.length + ' (все)'
            : numbers.length + ' из ' + batchNumbers.length;

        SAMPLE_UNKNOWN.hidden = foreign.length === 0;
        SAMPLE_UNKNOWN_LIST.textContent = foreign.join(', ');

        renderTotals(numbers);
    }

    /** Общий счётчик брака по всем группам. */
    function renderTotals(sampleNumbers) {
        const all = [];

        groups().forEach((group) => {
            readNumbers(parts(group).text).forEach((number) => {
                if (!all.includes(number)) {
                    all.push(number);
                }
            });
        });

        TOTAL_COUNTER.textContent = all.length
            ? all.length + ' яч. в ' + groups().length + ' гр.'
            : 'брака нет';

        markList(
            sampleNumbers === undefined ? readNumbers(SAMPLE_TEXT) : sampleNumbers,
            all
        );
    }

    /** Выбранное решение группы; {@code null}, если ни одно не отмечено. */
    function decisionOf(group) {
        const checked = group.querySelector(
            'input[name="defectGroups['
            + group.getAttribute('data-index')
            + '].decision"]:checked'
        );

        return checked ? checked.value : null;
    }

    /**
     * Проверка одной группы: чужие номера, номера вне выборки и
     * номера, у которых в другой группе выбрано другое решение.
     *
     * Сервис отклоняет карточку целиком, поэтому перечисляют все
     * проблемы сразу — исправлять их по одной после каждого отказа
     * контролёр будет слишком долго. Один номер может быть в разных
     * группах с разными причинами брака, но решение у ячейки может
     * быть только одно: её нельзя и вернуть поставщику, и оставить
     * в изоляторе.
     */
    function renderGroup(group) {
        const field = parts(group);
        const numbers = readNumbers(field.text);
        const sampleNumbers = readNumbers(SAMPLE_TEXT);
        const decision = decisionOf(group);

        const foreign = numbers.filter(
            (number) => !batchNumbers.includes(number)
        );

        const conflicting = new Set();

        if (decision) {
            groups().forEach((other) => {
                if (other === group) {
                    return;
                }

                const otherDecision = decisionOf(other);

                if (!otherDecision || otherDecision === decision) {
                    return;
                }

                const otherNumbers = readNumbers(parts(other).text);

                numbers.forEach((number) => {
                    if (otherNumbers.includes(number)) {
                        conflicting.add(number);
                    }
                });
            });
        }

        const unchecked = field.all.checked
            ? []
            : numbers.filter((number) => !sampleNumbers.includes(number));

        const problems = [];

        if (foreign.length) {
            problems.push('не из партии: ' + foreign.join(', '));
        }

        if (conflicting.size) {
            problems.push('в другой группе с другим решением: '
                + Array.from(conflicting).join(', '));
        }

        if (unchecked.length) {
            problems.push('нет в выборочном контроле: ' + unchecked.join(', '));
        }

        field.counter.textContent = field.all.checked
            ? batchNumbers.length + ' яч. (вся партия)'
            : numbers.length + ' яч.';

        field.unknown.hidden = problems.length === 0;
        field.unknown.textContent = problems.join('; ');

        renderTotals(sampleNumbers);
    }

    /*
     * Отметка «все ячейки» заполняет перечень полным диапазоном и
     * закрывает поле от правки: иначе контролёр боролся бы с полем,
     * смысл которого уже заменён отметкой. Значение при этом уезжает
     * на сервер, и сверка с партией проходит как обычно.
     */
    function bindAllCellsSwitch(group) {
        const field = parts(group);

        function apply() {
            if (field.all.checked) {
                field.text.value = batchNumbers.join('\n');
                field.text.readOnly = true;
            } else {
                field.text.readOnly = false;
            }

            renderGroup(group);
        }

        field.all.addEventListener('change', apply);

        if (field.all.checked) {
            apply();
        }
    }


    /* =========================================================
       ДЕРЕВО ПРИЧИН — СВОЁ В КАЖДОЙ ГРУППЕ
       ========================================================= */

    /*
     * Ступени создаются по мере выбора: следующая появляется только
     * под выбранной и содержит только её уточнения, поэтому набор
     * несвязанных причин в группу попасть не может. Имя select'а
     * привязано к группе — иначе две группы делили бы одну причину.
     */
    function addReasonLevel(group, parentId, selectedId) {
        const field = parts(group);
        const options = parentId === null
            ? roots.slice().sort(byName)
            : childrenOf(parentId);

        if (!options.length) {
            return null;
        }

        const level = document.createElement('div');
        level.className = 'bvk-reasons__level';

        const caption = document.createElement('span');
        caption.className = 'bvk-reasons__caption';
        caption.textContent = parentId === null
            ? 'Причина'
            : 'Следующая причина';

        const select = document.createElement('select');
        select.name = 'defectGroups[' + field.index + '].reasonIds';
        select.setAttribute('aria-label', caption.textContent);

        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = '— выберите причину —';
        select.appendChild(placeholder);

        options.forEach((reason) => {
            const option = document.createElement('option');

            option.value = reason.id;
            option.textContent = reason.custom
                ? reason.name + ' — укажите текст'
                : reason.name;

            select.appendChild(option);
        });

        if (selectedId) {
            select.value = selectedId;
        }

        select.addEventListener('change', () => {
            /* Всё, что выбрано ниже, теряет смысл: ветка сменилась. */
            dropLevelsAfter(select);
            addReasonLevel(group, select.value, null);
            syncReasons(group);
        });

        level.appendChild(caption);
        level.appendChild(select);
        field.stack.appendChild(level);

        return select;
    }

    function dropLevelsAfter(select) {
        const level = select.closest('.bvk-reasons__level');

        if (!level) {
            return;
        }

        let sibling = level.nextElementSibling;

        while (sibling) {
            const next = sibling.nextElementSibling;

            sibling.remove();
            sibling = next;
        }
    }

    function chosenSelects(group) {
        const field = parts(group);

        return Array.from(
            field.stack.querySelectorAll('select[name^="defectGroups"]')
        ).filter((select) => select.value);
    }

    /*
     * Ступень «Другое» требует текста и раскрывает поле своей группы:
     * написанное заводят в справочник причин, поэтому поле стоит сразу
     * под цепочкой, а не отдельным экраном.
     */
    function syncReasons(group) {
        const field = parts(group);
        const chain = chosenSelects(group);
        const last = chain.length ? chain[chain.length - 1] : null;
        const lastReason = last ? reasons.get(last.value) : null;

        field.custom.hidden = !(lastReason && lastReason.custom);

        if (field.custom.hidden) {
            field.customText.value = '';
        }

        renderGroup(group);
    }

    /**
     * Цепочка причин группы из data-chosen.
     *
     * Возврат страницы после отказа обязан показать уже выбранные
     * ступени, иначе контролёр собирал бы причину заново. Родителем
     * следующей ступени служит переданный идентификатор последней
     * выбранной причины: пустые ступени уточнений в цепочке не участвуют.
     */
    function restoreChain(group) {
        const field = parts(group);
        const chain = splitNumbers(group.getAttribute('data-chosen'));

        if (!chain.length) {
            addReasonLevel(group, null, null);

            return;
        }

        let parentId = null;

        chain.forEach((id) => {
            const reason = reasons.get(id);

            if (!reason || reason.parentId !== parentId) {
                return;
            }

            if (addReasonLevel(group, parentId, id)) {
                addReasonLevel(group, id, null);
            }

            parentId = id;
        });
    }


    /* =========================================================
       ГРУППЫ БРАКА: ДОБАВЛЕНИЕ И УДАЛЕНИЕ
       ========================================================= */

    /**
     * Чистая группа из скрытого шаблона.
     *
     * Сервер отдаёт экземпляр с маркером вместо индекса, потому что
     * настоящего номера группы в момент рендеринга ещё нет: подставляют
     * его здесь, причём до вставки в документ — иначе два одинаковых id
     * уже существовали бы в дереве.
     */
    function createGroup() {
        const index = groups().length;
        const fragment = GROUP_TEMPLATE.innerHTML
            .split(PLACEHOLDER)
            .join(String(index));

        const holder = document.createElement('div');

        holder.innerHTML = fragment.trim();

        return holder.querySelector('.bvk-group');
    }

    function bindGroup(group) {
        const field = parts(group);

        group.querySelector('[data-bvk-remove-group]')
            .addEventListener('click', () => {
                group.remove();
                reindex();
                renderSample();
            });

        field.text.addEventListener('input', () => renderGroup(group));

        bindAllCellsSwitch(group);
        restoreChain(group);
        syncReasons(group);
    }

    ADD_GROUP.addEventListener('click', () => {
        const group = createGroup();

        GROUPS.appendChild(group);
        reindex();
        bindGroup(group);
        renderSample();

        group.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });

    /*
     * Группы, которые отрисовал сервер (карточку открыли повторно или
     * вернули после отказа валидации), подключают к скрипту в том же
     * порядке: их имена и индексы уже проставлены.
     */
    groups().forEach(bindGroup);

    SAMPLE_TEXT.addEventListener('input', renderSample);
    SAMPLE_ALL.addEventListener('change', renderSample);


    /* =========================================================
       ОТПРАВКА
       ========================================================= */

    /*
     * Пустые поля выключают: незаполненная группа, добавленная «на
     * всякий случай», уехала бы на сервер как брак без причины и
     * заблокировала проверку, где брака нет.
     */
    FORM.addEventListener('submit', () => {
        groups().forEach((group) => {
            const field = parts(group);
            const filled = field.all.checked
                || readNumbers(field.text).length > 0
                || chosenSelects(group).length > 0
                || field.customText.value.trim().length > 0
                || Boolean(group.querySelector(
                    'input[name="defectGroups['
                    + field.index + '].decision"]:checked'
                ));

            field.stack.querySelectorAll('select').forEach((select) => {
                select.disabled = !filled || !select.value;
            });

            field.customText.disabled = !filled || field.custom.hidden;
            field.all.disabled = !filled;
            field.text.disabled = !filled || field.all.checked;
        });

        SAMPLE_TEXT.disabled = SAMPLE_ALL.checked;
    });

    reindex();
    renderSample();
})();