(() => {
    'use strict';

    /*
     * Глобальные уведомления системы.
     *
     * Смысл — сообщить сотруднику о новой работе, не заставляя его
     * переходить в раздел: раз в poll-интервал читается JSON счётчиков
     * доступных ему разделов, сравнение идёт с предыдущими значениями в
     * localStorage, и только рост счётчика рождает тост «Новое
     * "Приёмка с БВК" — 1» со звуком в правом нижнем углу.
     *
     * Механизм общий для всех интерфейсов: сервер отдаёт только те
     * разделы, к которым роль пользователя имеет доступ, поэтому тост
     * всегда ведёт туда, где сотрудник реально может работать, а не в
     * 403.
     *
     * localStorage, а не переменная в памяти, нужен потому, что
     * сотрудник ходит между страницами: страница перезагружается,
     * память обнуляется, а счётчики уже показанных работ должны
     * пережить переход — иначе при каждом переходе звучал бы сигнал по
     * всем открытым разделам сразу.
     *
     * Первый опрос (ключей нет) только запоминает значения и молчит:
     * «новым» считается рост уже известного счётчика, а не любое
     * ненулевое значение.
     */
    const POLL_URL = '/notifications/active';
    const STORAGE_KEY = 'appNotificationCounters';
    const POLL_INTERVAL_MS = 30000;

    /* Тост живёт, пока его не прочитали; дольше — засоряет экран. */
    const TOAST_LIFETIME_MS = 12000;

    /* Сколько тостов держать одновременно: длинная стопка не читаема. */
    const TOAST_LIMIT = 5;

    function readStored() {
        try {
            const raw = window.localStorage.getItem(STORAGE_KEY);

            return raw ? JSON.parse(raw) : {};
        } catch (error) {
            /*
             * Браузер может отказать в доступе (приватный режим,
             * отключённые хранилища). Без хранилища уведомление всё
             * равно полезно: тосты и звук будут на каждой проверке,
             * и это лучше, чем молчать совсем.
             */
            return {};
        }
    }

    function writeStored(values) {
        try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(values));
        } catch (error) {
            /* Хранилище переполнено или недоступно — не критично. */
        }
    }

    /*
     * Короткий двухтональный сигнал на Web Audio API.
     *
     * Отдельный файл .wav не заводится: тон синтезируется, поэтому
     * звук есть даже офлайн и без бинарника в репозитории. Частоты
     * высокие и длительность малая — сигнал слышно в цеху, но он не
     * превращается в помеху.
     *
     * Браузер блокирует звук до первого жеста пользователя; при
     * отказе контекста тост всё равно показывается — молчание лучше
     * сбоя скрипта.
     */
    let audioContext = null;

    function playChime() {
        try {
            const AudioCtor = window.AudioContext
                || window.webkitAudioContext;

            if (!AudioCtor) {
                return;
            }

            if (!audioContext) {
                audioContext = new AudioCtor();
            }

            if (audioContext.state === 'suspended') {
                audioContext.resume().catch(() => {});
            }

            const start = audioContext.currentTime;

            [880, 1320].forEach((frequency, index) => {
                const oscillator = audioContext.createOscillator();
                const gain = audioContext.createGain();

                oscillator.type = 'sine';
                oscillator.frequency.value = frequency;

                const from = start + index * 0.14;

                gain.gain.setValueAtTime(0.0001, from);
                gain.gain.exponentialRampToValueAtTime(0.18, from + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, from + 0.13);

                oscillator.connect(gain);
                gain.connect(audioContext.destination);

                oscillator.start(from);
                oscillator.stop(from + 0.15);
            });
        } catch (error) {
            /* Звук — вежливость интерфейса, а не его функция. */
        }
    }

    function ensureHost() {
        let host = document.getElementById('appNotifications');

        if (!host) {
            host = document.createElement('div');

            host.id = 'appNotifications';
            host.className = 'app-notifications';
            host.setAttribute('aria-live', 'polite');

            document.body.appendChild(host);
        }

        return host;
    }

    function buildToast(item) {
        const toast = document.createElement('a');

        toast.className = 'app-notification';
        toast.href = item.url;
        toast.dataset.key = item.key;

        const dot = document.createElement('span');

        dot.className = 'app-notification__dot';
        dot.setAttribute('aria-hidden', 'true');

        const body = document.createElement('span');

        body.className = 'app-notification__body';

        const label = document.createElement('span');

        label.className = 'app-notification__label';
        label.textContent = 'Новое «' + item.label + '»';

        const hint = document.createElement('small');

        hint.className = 'app-notification__hint';
        hint.textContent = 'Перейти в раздел';

        body.appendChild(label);
        body.appendChild(hint);

        const count = document.createElement('span');

        count.className = 'app-notification__count';
        count.textContent = item.delta;

        const close = document.createElement('span');

        close.className = 'app-notification__close';
        close.setAttribute('aria-label', 'Закрыть уведомление');
        close.textContent = '×';

        toast.appendChild(dot);
        toast.appendChild(body);
        toast.appendChild(count);
        toast.appendChild(close);

        /*
         * Крестик не должен вести по ссылке: он только снимает тост,
         * а переход — по клику на самой строке.
         */
        close.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            expire(toast);
        });

        return toast;
    }

    /*
     * Одинаковые разделы суммируются: строку собирают по ключу, и
     * повторное уведомление о том же разделе увеличивает число, а не
     * добавляет второй тост — два сигнала об одном деле сотрудник
     * прочёл бы как два разных дела.
     */
    function addToast(item) {
        const host = ensureHost();

        const existing = host.querySelector(
            '.app-notification[data-key="' + item.key + '"]'
        );

        if (existing) {
            const counter = existing.querySelector(
                '.app-notification__count'
            );

            if (counter) {
                counter.textContent =
                    Number(counter.textContent || 0) + item.delta;
            }

            /* Тост уже на экране: отсрочка его закрытия обновляется. */
            existing.classList.remove('is-leaving');
            scheduleExpire(existing);

            return;
        }

        const toast = buildToast(item);

        host.appendChild(toast);

        /*
         * Класс is-shown навешивается на следующем кадре:
         * иначе браузер не успевает отрисовать начальное состояние, и
         * всплывание проигрывается не всегда.
         */
        requestAnimationFrame(() => {
            toast.classList.add('is-shown');
        });

        scheduleExpire(toast);

        /* Длинная стопка не должна вытеснять остальные тосты. */
        while (host.children.length > TOAST_LIMIT) {
            host.removeChild(host.firstElementChild);
        }
    }

    function scheduleExpire(toast) {
        window.setTimeout(() => expire(toast), TOAST_LIFETIME_MS);
    }

    function expire(toast) {
        if (!toast || !toast.isConnected) {
            return;
        }

        toast.classList.add('is-leaving');

        window.setTimeout(() => {
            if (toast.isConnected) {
                toast.remove();
            }
        }, 400);
    }

    async function poll(known) {
        let items;

        try {
            const response = await fetch(POLL_URL, {
                credentials: 'same-origin',
                headers: {
                    'Accept': 'application/json'
                }
            });

            if (!response.ok) {
                return known;
            }

            items = await response.json();
        } catch (error) {
            /* Сеть или сессия: следующий интервал попробует снова. */
            return known;
        }

        const current = {};
        const fresh = [];

        items.forEach((item) => {
            current[item.key] = item.count;

            const previous = known[item.key];

            /*
             * Ключа нет — это первый опрос в этом браузере: значение
             * запоминают молча, иначе при каждой загрузке страницы
             * звучал бы сигнал по всем разделам сразу.
             */
            if (previous === undefined) {
                return;
            }

            if (item.count > previous) {
                fresh.push({
                    key: item.key,
                    label: item.label,
                    url: item.url,
                    delta: item.count - previous
                });
            }
        });

        writeStored(current);

        if (fresh.length > 0) {
            fresh.forEach(addToast);
            playChime();
        }

        return current;
    }

    function initializeNotifications() {
        let known = readStored();

        poll(known).then((values) => {
            known = values;

            window.setInterval(() => {
                poll(known).then((next) => {
                    known = next;
                });
            }, POLL_INTERVAL_MS);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initializeNotifications
        );
    } else {
        initializeNotifications();
    }
})();
