/*
 * Прокрутка страницы в виде датчика ДУУ2М (жёсткий).
 *
 * Единый орган управления — большой поплавок на стержне:
 * его положение = положение прокрутки страницы. Нативный скроллбар
 * страницы скрыт, чтобы «поплавков» не стало два.
 */
(function () {
  'use strict';

  if (window.__duumScroll) return;
  window.__duumScroll = true;

  var HEAD_H = 62;
  var NECK_TOP = 56;
  var NECK_H = 40;
  var THREAD_TOP = 92;
  var THREAD_H = 30;
  var FLANGE_TOP = 120;
  var FLANGE_H = 14;
  var TRACK_TOP = FLANGE_TOP + FLANGE_H;
  var BOTTOM_H = 42;
  var PAD_R = '68px';
  var KNOB_MIN = 150;
  var KNOB_MAX_RATIO = 0.5;

  var css = [
    '.duum{position:fixed;right:16px;top:50%;transform:translateY(-50%);',
    'width:46px;height:min(82vh,880px);min-height:420px;z-index:1500;',
    'display:none;pointer-events:none;',
    'font:600 11px/1 system-ui,sans-serif;user-select:none;touch-action:none;',
    'filter:drop-shadow(0 10px 22px rgba(0,0,0,.35));',
    'transition:opacity .25s ease}',
    '.duum.is-on{display:block}',
    '.duum.is-on>:not(.duum__value){pointer-events:auto}',
    '.duum.is-dim{opacity:.55}.duum.is-dim:hover{opacity:1}',
    '.duum__head{position:absolute;left:50%;top:0;transform:translateX(-50%);',
    'width:39px;height:' + HEAD_H + 'px;border-radius:50% 50% 46% 46%/62% 62% 40% 40%;',
    'border:1px solid #5b636b;cursor:pointer;',
    'background:radial-gradient(circle at 34% 24%,#ffffff 0 5%,#eef1f4 14%,',
    '#cfd6dc 38%,#a7afb7 62%,#7d858d 84%,#5f666e 100%)}',
    '.duum__head::before{content:"";position:absolute;left:50%;top:-4px;',
    'transform:translateX(-50%);width:29px;height:24px;border-radius:50%;',
    'border:1px solid #79818a;',
    'background:repeating-conic-gradient(from 0deg at 50% 50%,#d6dbe0 0 5deg,',
    '#adb5bc 5deg 10deg);',
    'box-shadow:inset 0 -4px 7px rgba(0,0,0,.28),inset 0 4px 5px rgba(255,255,255,.65)}',
    '.duum__head::after{content:"";position:absolute;right:-6px;top:26px;',
    'width:9px;height:15px;border-radius:3px;border:1px solid #5b636b;',
    'background:linear-gradient(180deg,#ccd3d9,#7f878f)}',
    '.duum__neck{position:absolute;left:50%;transform:translateX(-50%);',
    'top:' + NECK_TOP + 'px;width:10px;height:' + NECK_H + 'px;',
    'background:linear-gradient(90deg,#666d75,#e4e8ec 42%,#9aa2aa 68%,#5c636b)}',
    '.duum__neck::before{content:"";position:absolute;left:50%;top:8px;',
    'transform:translateX(-50%);width:15px;height:13px;border-radius:3px;',
    'border:1px solid #4e555c;',
    'background:linear-gradient(90deg,#666d75,#eef1f3 40%,#98a0a8 70%,#5c636b)}',
    '.duum__thread{position:absolute;left:50%;transform:translateX(-50%);',
    'top:' + THREAD_TOP + 'px;width:7px;height:' + THREAD_H + 'px;',
    'background:repeating-linear-gradient(180deg,#d8dde1 0 1px,#7c848c 1px 4px)}',
    '.duum__thread::after{content:"";position:absolute;inset:0;',
    'background:linear-gradient(90deg,rgba(0,0,0,.55),rgba(255,255,255,.5) 38%,',
    'rgba(0,0,0,.5))}',
    '.duum__flange{position:absolute;left:50%;transform:translateX(-50%);',
    'top:' + FLANGE_TOP + 'px;width:28px;height:' + FLANGE_H + 'px;',
    'border-radius:3px;border:1px solid #4e555c;',
    'background:linear-gradient(90deg,#666d75,#e6eaed 40%,#98a0a8 70%,#5c636b)}',
    '.duum__track{position:absolute;left:50%;transform:translateX(-50%);',
    'top:' + TRACK_TOP + 'px;bottom:' + BOTTOM_H + 'px;width:6px;',
    'border-radius:6px;cursor:pointer;',
    'background:linear-gradient(90deg,#08090a,#4b5257 28%,#23272b 52%,#040506)}',
    '.duum__bottom{position:absolute;left:50%;transform:translateX(-50%);',
    'bottom:0;width:14px;height:' + BOTTOM_H + 'px;cursor:pointer}',
    '.duum__bottom::before{content:"";position:absolute;left:50%;top:0;',
    'transform:translateX(-50%);width:14px;height:17px;border-radius:3px;',
    'border:1px solid #4e555c;',
    'background:linear-gradient(90deg,#666d75,#eef1f3 40%,#98a0a8 70%,#5c636b)}',
    '.duum__bottom::after{content:"";position:absolute;left:50%;bottom:0;',
    'transform:translateX(-50%);width:11px;height:20px;',
    'border-radius:3px 3px 8px 8px;border:1px solid #9aa0a5;',
    'background:linear-gradient(90deg,#c2c6ca,#ffffff 45%,#d9dcdf 72%,#b4b9bd)}',
    '.duum__float{position:absolute;left:50%;transform:translate(-50%,-50%);',
    'top:' + TRACK_TOP + 'px;width:33px;height:190px;padding:0;',
    'border:1px solid #59616a;border-radius:17px;cursor:grab;',
    'background:linear-gradient(90deg,#5c646c 0%,#a9b1b9 10%,#f6f8fa 24%,',
    '#dde3e7 38%,#aab2ba 60%,#7b838b 82%,#495057 100%);',
    'box-shadow:inset 0 0 0 1px rgba(255,255,255,.25),',
    'inset 0 14px 18px rgba(255,255,255,.35),',
    'inset 0 -16px 20px rgba(0,0,0,.28)}',
    '.duum__float:hover{filter:brightness(1.05)}',
    '.duum__float:active,.duum__float.is-drag{cursor:grabbing;',
    'box-shadow:inset 0 0 0 1px rgba(255,255,255,.35),0 0 0 3px rgba(90,140,190,.35)}',
    '.duum__collar{position:absolute;left:50%;transform:translateX(-50%);',
    'width:10px;height:11px;border-radius:3px;border:1px solid #4e555c;',
    'background:linear-gradient(90deg,#666d75,#eef1f3 40%,#98a0a8 70%,#5c636b)}',
    '.duum__collar--top{top:-7px}.duum__collar--bottom{bottom:-7px}',
    '.duum__float::after{content:"";position:absolute;left:4px;right:4px;',
    'top:50%;height:1px;background:linear-gradient(90deg,',
    'rgba(0,0,0,.05),rgba(0,0,0,.35) 32%,rgba(0,0,0,.12) 64%,rgba(0,0,0,.04))}',
    '.duum__value{position:absolute;right:52px;top:' + TRACK_TOP + 'px;',
    'transform:translateY(-50%);padding:4px 7px;border-radius:6px;',
    'background:rgba(18,22,26,.92);color:#e7ecf1;white-space:nowrap;',
    'opacity:0;transition:opacity .15s;pointer-events:none}',
    '.duum.is-live .duum__value{opacity:1}',
    'html.duum-active{scrollbar-width:none;-ms-overflow-style:none}',
    'html.duum-active::-webkit-scrollbar{width:0;height:0;display:none}',
    'html.duum-active .page-content,html.duum-active .content,html.duum-active .bvk-page,html.duum-active .sk-page{padding-right:' + PAD_R,
    '@media (max-height:760px){.duum__float{width:28px;border-radius:14px}',
    '.duum{width:40px}}}',
    '@media (max-width:900px){.duum.is-on{display:none}',
    'html.duum-active{scrollbar-width:auto}',
    'html.duum-active::-webkit-scrollbar{display:block}',
    'html.duum-active .page-content,html.duum-active .content,html.duum-active .bvk-page,html.duum-active .sk-page{padding-right:20px}}'
  ].join('');

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  function mount() {
    var root = document.createElement('div');
    root.className = 'duum';
    root.setAttribute('role', 'group');
    root.setAttribute('aria-label', 'Прокрутка страницы');
    root.innerHTML =
      '<span class="duum__head" title="Наверх"></span>' +
      '<span class="duum__neck"></span>' +
      '<span class="duum__thread"></span>' +
      '<span class="duum__flange"></span>' +
      '<span class="duum__track"></span>' +
      '<span class="duum__bottom" title="В конец"></span>' +
      '<button type="button" class="duum__float" ' +
      'aria-label="Поплавок: перетащите вверх или вниз для прокрутки">' +
      '<i class="duum__collar duum__collar--top"></i>' +
      '<i class="duum__collar duum__collar--bottom"></i>' +
      '</button>' +
      '<span class="duum__value">0%</span>';
    document.body.appendChild(root);

    var html = document.documentElement;
    var track = root.querySelector('.duum__track');
    var bottom = root.querySelector('.duum__bottom');
    var head = root.querySelector('.duum__head');
    var knob = root.querySelector('.duum__float');
    var value = root.querySelector('.duum__value');

    var dragging = false;
    var grabOffset = 0;
    var idleTimer = null;

    function scrollTop() {
      return window.pageYOffset || html.scrollTop || 0;
    }

    function maxScroll() {
      return html.scrollHeight - window.innerHeight;
    }

    function ratio() {
      var max = maxScroll();
      if (max <= 0) return 0;
      return Math.min(1, Math.max(0, scrollTop() / max));
    }

    function fullStroke() {
      return Math.max(1, track.offsetHeight);
    }

    function knobHeight() {
      var full = fullStroke();
      var doc = Math.max(1, html.scrollHeight);
      var ideal = full * (window.innerHeight / doc);
      var max = Math.max(KNOB_MIN, full * KNOB_MAX_RATIO);
      return Math.round(Math.min(Math.max(ideal, KNOB_MIN), max));
    }

    function stroke() {
      return Math.max(1, fullStroke() - knob.offsetHeight);
    }

    function sync() {
      var on = maxScroll() > 8;
      root.classList.toggle('is-on', on);
      html.classList.toggle('duum-active', on);
      if (!on) return;

      knob.style.height = knobHeight() + 'px';

      var top = track.offsetTop + knob.offsetHeight / 2 + ratio() * stroke();
      knob.style.top = top + 'px';
      value.style.top = top + 'px';
      value.textContent = Math.round(ratio() * 100) + '%';
    }

    function ratioFromY(clientY) {
      var rect = track.getBoundingClientRect();
      var r = (clientY - rect.top - knob.offsetHeight / 2 - grabOffset) / stroke();
      return Math.min(1, Math.max(0, r));
    }

    function scrollToRatio(clientY, smooth) {
      window.scrollTo({
        top: ratioFromY(clientY) * maxScroll(),
        behavior: smooth ? 'smooth' : 'auto'
      });
    }

    function wake() {
      root.classList.remove('is-dim');
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(function () {
        if (!dragging) root.classList.add('is-dim');
      }, 2200);
    }

    knob.addEventListener('pointerdown', function (e) {
      dragging = true;
      var rect = knob.getBoundingClientRect();
      grabOffset = e.clientY - (rect.top + rect.height / 2);
      knob.classList.add('is-drag');
      root.classList.add('is-live');
      wake();
      e.preventDefault();
    });

    window.addEventListener('pointermove', function (e) {
      if (!dragging) return;
      scrollToRatio(e.clientY, false);
      e.preventDefault();
    }, { passive: false });

    function stopDrag() {
      if (!dragging) return;
      dragging = false;
      grabOffset = 0;
      knob.classList.remove('is-drag');
      root.classList.remove('is-live');
      wake();
    }

    window.addEventListener('pointerup', stopDrag);
    window.addEventListener('pointercancel', stopDrag);
    window.addEventListener('blur', stopDrag);

    knob.addEventListener('keydown', function (e) {
      var step = window.innerHeight * 0.25;
      var to = null;
      if (e.key === 'ArrowDown' || e.key === 'PageDown') to = scrollTop() + step;
      else if (e.key === 'ArrowUp' || e.key === 'PageUp') to = scrollTop() - step;
      else if (e.key === 'Home') to = 0;
      else if (e.key === 'End') to = maxScroll();
      else return;
      e.preventDefault();
      window.scrollTo({ top: Math.max(0, to), behavior: 'smooth' });
      wake();
    });

    track.addEventListener('pointerdown', function (e) {
      grabOffset = 0;
      dragging = true;
      root.classList.add('is-live');
      wake();
      scrollToRatio(e.clientY, false);
    });

    head.addEventListener('pointerdown', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
      wake();
    });

    bottom.addEventListener('pointerdown', function () {
      window.scrollTo({ top: maxScroll(), behavior: 'smooth' });
      wake();
    });

    var queued = false;
    function requestSync() {
      if (queued) return;
      queued = true;
      requestAnimationFrame(function () {
        queued = false;
        sync();
      });
    }

    window.addEventListener('scroll', function () {
      requestSync();
      wake();
    }, { passive: true });
    window.addEventListener('resize', requestSync);

    if (window.ResizeObserver) {
      new ResizeObserver(requestSync).observe(document.body);
    }

    wake();
    sync();
    window.addEventListener('load', sync);
  }

  document.readyState === 'loading'
    ? document.addEventListener('DOMContentLoaded', mount)
    : mount();
})();
