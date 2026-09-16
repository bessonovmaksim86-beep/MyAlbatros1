(() => {
    'use strict';

    /*
     * Модуль страниц техпроцессов:
     *  1) конструктор маршрута (строки операций, нормы, зависимости);
     *  2) каскадный выбор изделия классификатора;
     *  3) подтверждение удаления.
     *
     * ВАЖНО: весь человекочитаемый текст (кириллица) берётся из
     * data-атрибутов HTML, а не из литералов этого файла.
     * Статика отдаётся без charset, поэтому кириллица прямо в JS
     * отображалась бы «кракозябрами».
     */

    const form = document.querySelector('[data-tp-form]');
    const rowsBox = document.querySelector('[data-tp-rows]');
    const rowTemplate = document.querySelector('[data-tp-row-template]');
    const addButton = document.querySelector('[data-tp-add-row]');
    const dependencyTemplate =
        document.querySelector('[data-tp-dep-template]');
    const workplaceRequiredTemplate =
        document.querySelector('[data-tp-workplace-required-message]');
    const workplaceTemplate =
        document.querySelector('[data-tp-workplace-template]');

    const operationNames = new Map();

    function collectOperationNames() {
        document
            .querySelectorAll('[data-operation-catalog] option')
            .forEach(option => {
                if (option.value) {
                    operationNames.set(
                        option.value,
                        option.dataset.name || option.textContent.trim()
                    );
                }
            });
    }

    const rows = () =>
        [...rowsBox.children].filter(element =>
            element.classList.contains('tp-row')
        );

    function operationSelect(row) {
        return row.querySelector('[data-tp-operation]');
    }

    function normFields(row) {
        return [
            row.querySelector('[data-tp-labor]'),
            row.querySelector('[data-tp-machine]'),
            row.querySelector('[data-tp-limit]')
        ].filter(Boolean);
    }

    function syncRow(row) {
        const select = operationSelect(row);
        const enabled = Boolean(select.value);

        /*
         * Сам select операции остаётся активным всегда, иначе
         * пустую строку нельзя было бы заполнить. Отключаем
         * только поля норм.
         */
        normFields(row).forEach(field => {
            field.disabled = !enabled;
            field.required = enabled;
        });

        row.querySelector('[data-tp-deps]')?.toggleAttribute(
            'hidden',
            !enabled
        );

        /*
         * Рабочее место и описание относятся к конкретной операции,
         * поэтому скрыты, пока операция не выбрана.
         */
        row.querySelector('.tp-row-places')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.querySelector('.tp-row-note')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.querySelector('[data-tp-remove]')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.classList.toggle('is-empty', !enabled);
    }

    function depsEmptyText() {
        /*
         * Текст берётся из <template data-tp-dep-template> —
         * он лежит в HTML и декодируется корректно.
         */
        return dependencyTemplate
            ? dependencyTemplate.textContent.trim()
            : '';
    }

    function rebuildDependencies() {
        const current = rows();
        const selected = new Map();

        current.forEach(row => {
            const value = operationSelect(row).value;

            if (value) {
                selected.set(row, value);
            }
        });

        current.forEach(row => {
            const box = row.querySelector('[data-tp-deps]');

            if (!box) {
                return;
            }

            const own = selected.get(row);

            const checked = new Set(
                [...box.querySelectorAll('input:checked')]
                    .map(input => input.value)
            );

            box.replaceChildren();

            const candidates = [...selected.entries()]
                .filter(([, value]) => value !== own);

            if (candidates.length === 0) {
                const hint = document.createElement('span');

                hint.className = 'tp-deps-empty';
                hint.textContent = depsEmptyText();

                box.append(hint);
                return;
            }

            candidates.forEach(([, value]) => {
                const label = document.createElement('label');

                label.className = 'tp-dep-check';

                const input = document.createElement('input');

                input.type = 'checkbox';
                input.value = value;
                input.checked = checked.has(value);
                input.dataset.dep = '1';

                label.append(
                    input,
                    document.createTextNode(
                        operationNames.get(value) || value
                    )
                );

                box.append(label);
            });
        });
    }

    function cleanupDependencies() {
        const alive = new Set(
            rows()
                .map(row => operationSelect(row).value)
                .filter(Boolean)
        );

        rows().forEach(row => {
            row.querySelectorAll('[data-tp-deps] input').forEach(input => {
                if (!alive.has(input.value)) {
                    input.checked = false;
                }
            });
        });
    }

    function renumber() {
        let index = 0;

        rows().forEach(row => {
            const select = operationSelect(row);

            [...[operationSelect(row)], ...normFields(row)]
                .flat()
                .forEach(field => {
                    if (!select.value) {
                        field.removeAttribute('name');
                        return;
                    }

                    const property = field.dataset.tpField;

                    field.name = `operations[${index}].${property}`;
                });

            /*
             * Поле описания операции в маршруте.
             */
            const noteField = row.querySelector(
                '.tp-row-note [data-tp-field="note"]'
            );

            if (noteField) {
                noteField.name = select.value
                    ? `operations[${index}].note`
                    : '';

                if (!select.value) {
                    noteField.removeAttribute('name');
                }
            }

            /*
             * Work places: every filled select is submitted as a
             * separate value of the same list property. The trailing
             * empty select has no name, so it is not submitted.
             */
            row.querySelectorAll('[data-tp-workplace]')
                .forEach(placeSelect => {
                    if (!select.value || !placeSelect.value) {
                        placeSelect.removeAttribute('name');
                    } else {
                        placeSelect.name =
                            `operations[${index}].workPlaceIds`;
                    }
                });

            row.querySelectorAll('[data-tp-deps] input').forEach(input => {
                if (!select.value) {
                    input.removeAttribute('name');
                    return;
                }

                input.name =
                    `operations[${index}].predecessorOperationIds`;
            });

            const badge = row.querySelector('[data-tp-row-number]');

            if (badge) {
                badge.textContent = String(index + 1);
            }

            if (select.value) {
                index++;
            }
        });
    }

    function bindRow(row) {
        if (row.dataset.bound === '1') {
            return;
        }

        row.dataset.bound = '1';

        operationSelect(row).addEventListener('change', normalize);

        row.querySelector('[data-tp-remove]').addEventListener(
            'click',
            () => {
                row.remove();
                normalize();
            }
        );

        row.querySelectorAll('[data-tp-deps]').forEach(box => {
            box.addEventListener('change', renumber);
        });
    }

    /*
     * Work places are added one row at a time: as soon as the last
     * select gets a value, an empty row appears below it, so the
     * next place can be chosen right away.
     */
    function workplaceSelects(row) {
        return [...row.querySelectorAll('[data-tp-workplace]')];
    }

    function ensureWorkplaceRow(row) {
        const box = row.querySelector('[data-tp-workplaces]');

        if (!box || !workplaceTemplate || workplaceSelects(row).length === 0) {
            return;
        }

        const selects = workplaceSelects(row);
        const last = selects[selects.length - 1];

        if (last.value) {
            box.append(workplaceTemplate.content.cloneNode(true));
        }
    }

    function handleWorkplaceChange(row) {
        ensureWorkplaceRow(row);
        renumber();
    }

    function handleWorkplaceRemove(row, button) {
        const placeRow = button.closest('.tp-workplace-row');

        if (!placeRow) {
            return;
        }

        placeRow.remove();

        /* An operation always needs at least one place to pick. */
        if (workplaceSelects(row).length === 0 && workplaceTemplate) {
            row.querySelector('[data-tp-workplaces]')
                ?.append(workplaceTemplate.content.cloneNode(true));
        }

        renumber();
    }

    function addRow() {
        rowsBox.append(rowTemplate.content.cloneNode(true));

        const row = rowsBox.lastElementChild;

        bindRow(row);
        syncRow(row);

        return row;
    }

    function normalize() {
        rows().forEach(bindRow);
        rows().forEach(syncRow);
        cleanupDependencies();

        rows()
            .filter(row => !operationSelect(row).value)
            .slice(1)
            .forEach(row => row.remove());

        if (!rows().some(row => !operationSelect(row).value)) {
            addRow();
        }

        /*
         * Rows loaded from the server already have places chosen,
         * so an empty row is appended right away for the next one.
         */
        rows().forEach(ensureWorkplaceRow);

        rebuildDependencies();
        renumber();
    }

    /*
     * Проверка перед отправкой: у каждой заполненной операции
     * должно быть выбрано рабочее место.
     * Текст сообщения берётся из <template> в HTML.
     */
    function validateWorkPlaces() {
        const missing = rows().some(row => {
            if (!operationSelect(row).value) {
                return false;
            }

            return !workplaceSelects(row).some(select => select.value);
        });

        if (!missing) {
            return true;
        }

        window.alert(
            workplaceRequiredTemplate
                ? workplaceRequiredTemplate.textContent.trim()
                : ''
        );

        return false;
    }

    function initBuilder() {
        if (!form || !rowsBox || !rowTemplate) {
            return;
        }

        collectOperationNames();
        normalize();

        /*
         * Work place selects are created on the fly, so their
         * events are handled through delegation on the rows box.
         */
        rowsBox.addEventListener('change', event => {
            const select = event.target.closest('[data-tp-workplace]');

            if (!select) {
                return;
            }

            const row = select.closest('.tp-row');

            if (row) {
                handleWorkplaceChange(row);
            }
        });

        rowsBox.addEventListener('click', event => {
            const button = event.target.closest(
                '[data-tp-workplace-remove]'
            );

            if (!button) {
                return;
            }

            const row = button.closest('.tp-row');

            if (row) {
                handleWorkplaceRemove(row, button);
            }
        });

        addButton?.addEventListener('click', () => {
            addRow();
            normalize();
        });

        form.addEventListener('submit', event => {
            renumber();

            if (!validateWorkPlaces()) {
                event.preventDefault();
            }
        });
    }

    /*
     * Каскадный выбор изделия классификатора:
     * вид изделия -> класс датчика -> конкретное изделие.
     * Подписи пунктов берутся из data-атрибутов источника.
     */
    function initProductPicker() {
        const dataSource =
            document.querySelector('[data-tp-classifier-data]');
        const typePicker =
            document.querySelector('[data-tp-type-picker]');
        const sensorField =
            document.querySelector('[data-tp-sensor-field]');
        const sensorPicker =
            document.querySelector('[data-tp-sensor-picker]');
        const classifierPicker =
            document.querySelector('[data-tp-classifier-picker]');

        if (!dataSource || !typePicker || !classifierPicker) {
            return;
        }

        const texts = dataSource.dataset || {};

        const items = [...dataSource.querySelectorAll('li')].map(item => ({
            id: item.dataset.id,
            code: item.dataset.code,
            type: item.dataset.type,
            sensorClassId: item.dataset.sensorClassId || '',
            sensorClassName: item.dataset.sensorClassName || '',
            name: item.dataset.name || ''
        }));

        const targetInput =
            document.querySelector('[data-tp-target-input]');
        const continueButton =
            document.querySelector('[data-tp-continue]');

        const option = (value, text) => {
            const element = document.createElement('option');

            element.value = value;
            element.textContent = text;

            return element;
        };

        const separator = ' ' + (texts.separator || '') + ' ';

        const label = item => {
            const parts = [];

            if (texts.codePrefix) {
                parts.push(texts.codePrefix + ' ' + item.code);
            } else {
                parts.push(String(item.code));
            }

            if (item.sensorClassName) {
                parts.push(item.sensorClassName);
            }

            if (item.name) {
                parts.push(item.name);
            }

            return parts.join(separator);
        };

        const matches = item => {
            if (typePicker.value && item.type !== typePicker.value) {
                return false;
            }

            if (sensorPicker
                && sensorPicker.value
                && item.sensorClassId !== sensorPicker.value) {
                return false;
            }

            return true;
        };

        const isSensorType = () => typePicker.value === 'SENSOR';

        const syncSensorVisibility = () => {
            if (!sensorField || !sensorPicker) {
                return;
            }

            sensorField.hidden = !isSensorType();

            if (!isSensorType()) {
                sensorPicker.value = '';
            }
        };

        const rebuildSensors = () => {
            if (!sensorPicker) {
                return;
            }

            const previous = sensorPicker.value;

            sensorPicker.replaceChildren(
                option('', texts.sensorClassLabel || '')
            );

            const seen = new Set();

            items
                .filter(item =>
                    item.type === 'SENSOR'
                    && item.sensorClassId
                    && (!typePicker.value
                        || typePicker.value === 'SENSOR'))
                .forEach(item => {
                    if (seen.has(item.sensorClassId)) {
                        return;
                    }

                    seen.add(item.sensorClassId);
                    sensorPicker.append(
                        option(item.sensorClassId, item.sensorClassName)
                    );
                });

            if ([...sensorPicker.options]
                    .some(o => o.value === previous)) {
                sensorPicker.value = previous;
            }
        };

        const rebuildClassifiers = () => {
            const previous = classifierPicker.value;

            classifierPicker.replaceChildren(
                option('', texts.classifierLabel || '')
            );

            items.filter(matches).forEach(item => {
                classifierPicker.append(option(item.id, label(item)));
            });

            if ([...classifierPicker.options]
                    .some(o => o.value === previous)) {
                classifierPicker.value = previous;
            }
        };

        const syncContinue = () => {
            if (targetInput) {
                targetInput.value = classifierPicker.value;
            }

            if (continueButton) {
                continueButton.disabled = !classifierPicker.value;
            }
        };

        const refresh = () => {
            syncSensorVisibility();
            rebuildSensors();
            rebuildClassifiers();
            syncContinue();
        };

        typePicker.addEventListener('change', refresh);
        sensorPicker?.addEventListener('change', () => {
            rebuildClassifiers();
            syncContinue();
        });
        classifierPicker.addEventListener('change', syncContinue);

        continueButton?.addEventListener('click', event => {
            if (!classifierPicker.value) {
                return;
            }

            /*
             * Страница копирования: скрытое поле уже содержит изделие,
             * кнопка — submit формы, редирект не требуется.
             */
            if (targetInput) {
                return;
            }

            event.preventDefault();

            const base = window.TP_CREATE_NEW_URL
                || '/technology/techprocess/create/new';

            location.assign(
                base + '?classifierId='
                + encodeURIComponent(classifierPicker.value)
            );
        });

        refresh();
    }

    function initDelete() {
        const deleteForm =
            document.querySelector('[data-tp-delete-form]');

        deleteForm?.addEventListener('submit', event => {
            const message = deleteForm.dataset.confirm || '';

            if (!window.confirm(message)) {
                event.preventDefault();
            }
        });
    }

    /*
     * Graph arrows of the route.
     *
     * Orthogonal routing only (right angles). Every arrow leaves the
     * right edge of its source, runs into the EMPTY GAP between the
     * source column and the target column, turns once there and
     * enters the left edge of the target. The vertical part always
     * sits strictly inside a gap between columns, so an arrow can
     * never run over a node body.
     *
     * Slots on node edges: several arrows of one node get parallel
     * lines ordered by the height of the opposite end, so they
     * converge into one block the same way as on the reference
     * picture and never cross near the node. Arrows sharing the same
     * gap get separate channels a few pixels apart, so their lines
     * never overlap. When a link jumps over a whole column, its
     * horizontal part is moved to the closest free corridor between
     * rows (freeY), so it does not cut through blocks either.
     *
     * Edges come from data-edges ("0-1,1-2"), node rectangles are
     * measured from the rendered DOM.
     */
    function initGraphLinks() {
        const graph = document.querySelector('[data-tp-graph]');
        const svg = document.querySelector('[data-tp-graph-links]');

        if (!graph || !svg) {
            return;
        }

        const SVG_NS = 'http://www.w3.org/2000/svg';
        const SLOT_STEP = 12;
        const CHANNEL_STEP = 7;
        const GAP_MARGIN = 12;

        const draw = () => {
            const box = graph.getBoundingClientRect();

            svg.setAttribute('viewBox',
                '0 0 ' + box.width + ' ' + box.height);

            svg.querySelectorAll('path.tp-link')
                .forEach(path => path.remove());

            const edges = (svg.dataset.edges || '')
                .split(',')
                .filter(Boolean)
                .map(pair => pair.split('-').map(Number))
                .filter(pair => pair.length === 2);

            /*
             * 1. Rectangles of all nodes in graph coordinates.
             */
            const nodes = new Map();
            const items = [];

            graph.querySelectorAll('.tp-node').forEach(node => {
                const shape = node.querySelector('.tp-node-shape');

                if (!shape) {
                    return;
                }

                const rect = shape.getBoundingClientRect();
                const item = {
                    id: node.dataset.nodeId,
                    left: rect.left - box.left,
                    right: rect.right - box.left,
                    top: rect.top - box.top,
                    bottom: rect.bottom - box.top,
                    middle: rect.top + rect.height / 2 - box.top
                };

                nodes.set(item.id, item);
                items.push(item);
            });

            /*
             * 2. Column borders. A column is the set of nodes whose
             * horizontal ranges overlap; the vertical channel of a
             * link is placed in the gap between two neighboring
             * columns, so its exact borders matter.
             */
            const inSameColumn = (a, b) =>
                a.left < b.right - 1 && a.right > b.left + 1;

            const columnRight = (rect) => items.reduce(
                (max, other) => inSameColumn(rect, other)
                    ? Math.max(max, other.right)
                    : max,
                rect.right
            );

            const columnLeft = (rect) => items.reduce(
                (min, other) => inSameColumn(rect, other)
                    ? Math.min(min, other.left)
                    : min,
                rect.left
            );

            const nextColumnLeft = (rect) => items.reduce(
                (min, other) => other.left > rect.right + 1
                    ? Math.min(min, other.left)
                    : min,
                Infinity
            );

            const prevColumnRight = (rect) => items.reduce(
                (max, other) => other.right < rect.left - 1
                    ? Math.max(max, other.right)
                    : max,
                -Infinity
            );

            /*
             * 3. Helpers for horizontal segments: which y is free of
             * nodes between two x values.
             */
            const blocked = (xa, xb, y) => items.some(item =>
                item.left < xb - 2
                && item.right > xa + 2
                && y > item.top - 4
                && y < item.bottom + 4
            );

            const freeY = (xa, xb, y) => {
                if (!blocked(xa, xb, y)) {
                    return y;
                }

                let best = y;
                let bestDistance = Infinity;

                items.forEach(item => {
                    if (item.left >= xb - 2 || item.right <= xa + 2) {
                        return;
                    }

                    [item.top - 10, item.bottom + 10].forEach(candidate => {
                        const distance = Math.abs(candidate - y);

                        if (distance < bestDistance
                            && !blocked(xa, xb, candidate)) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    });
                });

                return best;
            };

            /*
             * 4. Edge slots on node edges, ordered by the height of
             * the opposite end of the link.
             */
            const outgoing = new Map();
            const incoming = new Map();

            const push = (map, id, link) => {
                if (!map.has(id)) {
                    map.set(id, []);
                }

                map.get(id).push(link);
            };

            const edgeKey = (pair) => pair[0] + '-' + pair[1];

            edges.forEach(pair => {
                const from = nodes.get(String(pair[0]));
                const to = nodes.get(String(pair[1]));

                if (!from || !to) {
                    return;
                }

                push(outgoing, String(pair[0]),
                    { edge: edgeKey(pair), opposite: to });
                push(incoming, String(pair[1]),
                    { edge: edgeKey(pair), opposite: from });
            });

            const slotOf = (map, id, edge) => {
                const links = (map.get(id) || []).slice().sort(
                    (a, b) => a.opposite.middle - b.opposite.middle
                );

                let index = links.findIndex(link => link.edge === edge);

                if (index < 0) {
                    index = 0;
                }

                return { index: index, total: links.length };
            };

            const slotY = (rect, index, total) => {
                const y = rect.middle
                    + (index - (total - 1) / 2) * SLOT_STEP;

                return Math.min(Math.max(y, rect.top + 8), rect.bottom - 8);
            };

            /*
             * 5. Plan every link: which gap holds its vertical part.
             * A link to the neighboring column uses one channel in
             * the gap right after its source column; a link jumping
             * over columns uses two channels, one per crossed gap.
             */
            const plans = [];

            edges.forEach(pair => {
                const from = nodes.get(String(pair[0]));
                const to = nodes.get(String(pair[1]));

                if (!from || !to) {
                    return;
                }

                const out = slotOf(outgoing, String(pair[0]), edgeKey(pair));
                const inn = slotOf(incoming, String(pair[1]), edgeKey(pair));

                const y1 = slotY(from, out.index, out.total);
                const y2 = slotY(to, inn.index, inn.total);

                const gapLeft = columnRight(from);
                const gapRight = nextColumnLeft(from);

                if (!Number.isFinite(gapRight)) {
                    return;
                }

                const firstKey = 'a' + Math.round(gapLeft);
                const firstBase = (gapLeft + gapRight) / 2;

                /*
                 * Target sits in the very next column when its left
                 * border matches the border the first gap ends at.
                 */
                const neighboring =
                    columnLeft(to) >= gapRight - 1
                    && !items.some(item =>
                        item.left > from.right + 2 && item.right < to.left - 2);

                const plan = {
                    x1: from.right,
                    x2: to.left,
                    y1: y1,
                    y2: y2,
                    direct: Math.abs(y1 - y2) < 1.5 && !blocked(from.right, to.left, y1),
                    channels: [{
                        key: firstKey,
                        base: firstBase,
                        lo: gapLeft + GAP_MARGIN,
                        hi: gapRight - GAP_MARGIN,
                        x: firstBase
                    }],
                    second: null
                };

                if (!neighboring) {
                    const secondGapLeft = prevColumnRight(to);
                    const secondGapRight = columnLeft(to);

                    if (Number.isFinite(secondGapLeft)) {
                        plan.second = {
                            key: 'b' + Math.round(secondGapRight),
                            base: (secondGapLeft + secondGapRight) / 2,
                            lo: secondGapLeft + GAP_MARGIN,
                            hi: secondGapRight - GAP_MARGIN,
                            x: (secondGapLeft + secondGapRight) / 2
                        };

                        plan.channels.push(plan.second);
                    } else {
                        plan.neighboringFallback = true;
                    }
                }

                plans.push(plan);
            });

            /*
             * 6. Spread channels of links sharing the same gap so
             * that their vertical parts never overlap.
             */
            const byKey = new Map();

            plans.forEach(plan => plan.channels.forEach(channel => {
                if (!byKey.has(channel.key)) {
                    byKey.set(channel.key, []);
                }

                byKey.get(channel.key).push(channel);
            }));

            byKey.forEach(channels => {
                const total = channels.length;

                channels.forEach((channel, index) => {
                    const offset = (index - (total - 1) / 2) * CHANNEL_STEP;

                    channel.x = Math.min(
                        Math.max(channel.base + offset, channel.lo),
                        channel.hi
                    );
                });
            });

            /*
             * 7. Draw: every path ends exactly in its target slot.
             */
            plans.forEach(plan => {
                const path = document.createElementNS(SVG_NS, 'path');
                let d;

                if (plan.direct) {
                    d = 'M ' + plan.x1 + ' ' + plan.y1
                        + ' H ' + (plan.x2 - 1);
                } else if (plan.second) {
                    const first = plan.channels[0].x;
                    const second = plan.second.x;
                    const midY = freeY(first, second, (plan.y1 + plan.y2) / 2);

                    d = 'M ' + plan.x1 + ' ' + plan.y1
                        + ' H ' + first
                        + ' V ' + midY
                        + ' H ' + second
                        + ' V ' + plan.y2
                        + ' H ' + (plan.x2 - 1);
                } else {
                    const channel = plan.channels[0].x;

                    d = 'M ' + plan.x1 + ' ' + plan.y1
                        + ' H ' + channel
                        + ' V ' + plan.y2
                        + ' H ' + (plan.x2 - 1);
                }

                path.setAttribute('class', 'tp-link');
                path.setAttribute('d', d);
                path.setAttribute('marker-end', 'url(#tp-arrow-head)');

                svg.append(path);
            });
        };

        const redrawLater = () => {
            window.requestAnimationFrame(draw);
        };

        redrawLater();

        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(draw);
        }

        window.addEventListener('resize', redrawLater);
    }

    /*
     * Each builder module is independent: a failure in one (for
     * example a page without a picker) must not stop the graph
     * arrows from being drawn.
     */
    function runSafely(init) {
        try {
            init();
        } catch (error) {
            if (window.console) {
                window.console.error(error);
            }
        }
    }

    function init() {
        runSafely(initBuilder);
        runSafely(initProductPicker);
        runSafely(initDelete);
        runSafely(initGraphLinks);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init, { once: true });
    } else {
        init();
    }
})();