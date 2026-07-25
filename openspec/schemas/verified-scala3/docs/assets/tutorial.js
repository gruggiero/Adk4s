/* verified-scala3 tutorial — shared behaviour
   No dependencies. Everything degrades gracefully without JS. */
(function () {
  "use strict";

  /* ── theme ──────────────────────────────────────────────────────────── */
  var saved = null;
  try { saved = localStorage.getItem("vs3-theme"); } catch (e) {}
  if (saved) document.documentElement.setAttribute("data-theme", saved);

  function initTheme() {
    var btn = document.querySelector(".theme-toggle");
    if (!btn) return;
    function label() {
      var t = document.documentElement.getAttribute("data-theme");
      btn.textContent = t === "dark" ? "☀ light" : t === "light" ? "☾ dark" : "◐ theme";
    }
    label();
    btn.addEventListener("click", function () {
      var cur = document.documentElement.getAttribute("data-theme");
      var next = cur === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      try { localStorage.setItem("vs3-theme", next); } catch (e) {}
      label();
    });
  }

  /* ── table of contents + scroll spy ─────────────────────────────────── */
  function initToc() {
    var toc = document.getElementById("toc");
    var main = document.querySelector("main");
    if (!toc || !main) return;
    var heads = main.querySelectorAll("h2, h3");
    var items = [];
    heads.forEach(function (h, i) {
      if (!h.id) h.id = "s" + i + "-" + (h.textContent || "").toLowerCase()
        .replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40);
      var li = document.createElement("li");
      if (h.tagName === "H3") li.className = "h3";
      var a = document.createElement("a");
      a.href = "#" + h.id;
      a.textContent = h.textContent;
      li.appendChild(a);
      toc.appendChild(li);
      items.push({ h: h, a: a });
    });
    if (!items.length) { var t = document.querySelector(".toc-title"); if (t) t.style.display = "none"; return; }

    var spy = function () {
      var best = null, top = 110;
      items.forEach(function (it) {
        var r = it.h.getBoundingClientRect();
        if (r.top <= top) best = it;
      });
      items.forEach(function (it) { it.a.classList.toggle("active", it === best); });
    };
    window.addEventListener("scroll", spy, { passive: true });
    spy();
  }

  /* ── tabs ───────────────────────────────────────────────────────────── */
  function initTabs() {
    document.querySelectorAll(".tabs").forEach(function (box) {
      var bar = box.querySelector(".tab-bar");
      var panels = Array.prototype.slice.call(box.querySelectorAll(".tab-panel"));
      if (!bar || !panels.length) return;
      var btns = Array.prototype.slice.call(bar.querySelectorAll("button"));
      function show(i) {
        btns.forEach(function (b, j) { b.setAttribute("aria-selected", String(i === j)); });
        panels.forEach(function (p, j) { p.setAttribute("data-active", String(i === j)); });
      }
      btns.forEach(function (b, i) { b.addEventListener("click", function () { show(i); }); });
      show(0);
    });
  }

  /* ── quizzes ────────────────────────────────────────────────────────── */
  function initQuiz() {
    document.querySelectorAll(".quiz[data-quiz]").forEach(function (host) {
      var src = document.getElementById(host.getAttribute("data-quiz"));
      if (!src) return;
      var qs;
      try { qs = JSON.parse(src.textContent); } catch (e) { return; }
      qs.forEach(function (q) {
        var card = document.createElement("div");
        card.className = "q";
        var qt = document.createElement("div");
        qt.className = "qtext";
        qt.textContent = q.q;
        card.appendChild(qt);
        var opts = document.createElement("div");
        opts.className = "opts";
        var why = document.createElement("div");
        why.className = "why";
        q.options.forEach(function (opt, i) {
          var b = document.createElement("button");
          b.className = "opt";
          b.type = "button";
          b.textContent = opt;
          b.addEventListener("click", function () {
            if (card.dataset.done) return;
            card.dataset.done = "1";
            opts.querySelectorAll(".opt").forEach(function (o, j) {
              if (j === q.answer) o.classList.add("correct");
              else if (j === i) o.classList.add("wrong");
            });
            why.innerHTML = (i === q.answer ? "<strong>Correct.</strong> " : "<strong>Not quite.</strong> ") + q.why;
            why.classList.add("show");
          });
          opts.appendChild(b);
        });
        card.appendChild(opts);
        card.appendChild(why);
        host.appendChild(card);
      });
    });
  }

  /* ── clickable pipeline ─────────────────────────────────────────────── */
  function initPipelines() {
    document.querySelectorAll("[data-pipeline]").forEach(function (host) {
      var src = document.getElementById(host.getAttribute("data-pipeline"));
      if (!src) return;
      var data;
      try { data = JSON.parse(src.textContent); } catch (e) { return; }
      var row = document.createElement("div");
      row.className = "pipeline";
      var detail = document.createElement("div");
      detail.className = "pipe-detail";
      var btns = [];
      function show(i) {
        btns.forEach(function (b, j) { b.classList.toggle("on", i === j); });
        detail.innerHTML = "<h5>" + data[i].label + "</h5>" + data[i].body;
      }
      data.forEach(function (n, i) {
        var b = document.createElement("button");
        b.className = "pipe-node";
        b.type = "button";
        b.textContent = n.short || n.label;
        b.addEventListener("click", function () { show(i); });
        row.appendChild(b);
        btns.push(b);
      });
      host.appendChild(row);
      host.appendChild(detail);
      show(0);
    });
  }

  /* ── hotspot drill ──────────────────────────────────────────────────── */
  function initDrills() {
    document.querySelectorAll(".drill").forEach(function (host) {
      var found = 0;
      var targets = host.querySelectorAll(".hot[data-bug]");
      var status = host.querySelector(".drill-status");
      var total = targets.length;
      function update() {
        if (!status) return;
        status.innerHTML = found >= total
          ? '<span class="tag-pass">All ' + total + " found.</span> " + (host.dataset.done || "")
          : "Found <strong>" + found + "</strong> of " + total + ".";
      }
      host.querySelectorAll(".hot").forEach(function (h) {
        h.addEventListener("click", function () {
          if (h.dataset.clicked) return;
          h.dataset.clicked = "1";
          var isBug = h.hasAttribute("data-bug");
          h.classList.add(isBug ? "found" : "dud");
          if (isBug) found++;
          var note = document.createElement("div");
          note.className = "verdict " + (isBug ? "fail" : "");
          note.innerHTML = '<div class="vhead">' + (isBug ? "Defect" : "Not a defect") + "</div>" +
            (h.getAttribute("data-why") || "");
          var slot = host.querySelector(".drill-notes");
          if (slot) slot.appendChild(note);
          update();
        });
      });
      update();
    });
  }

  /* ── spec-lint simulator (faithful port of scanner/spec-lint.sh) ────── */
  var STOP_VAGUE = /(^|[^a-z0-9])(valid|fast|reasonable|correct|appropriate)([^a-z0-9]|$)/i;

  function lintSpec(text) {
    var lines = text.split(/\r?\n/);
    var out = [];
    var reqs = [];          // {title, line, hasNorm, seenGiven, negative, scenarios}
    var props = [];         // {name, line, hasGen}
    var temps = [];         // {name, line, trig, resp}
    var cur = null, curKind = null;
    var inPo = false, hasPo = false, poRows = 0;
    var covered = {}, ordinalRefs = 0, titleRefs = 0;

    function push() { cur = null; curKind = null; }

    lines.forEach(function (raw, idx) {
      var n = idx + 1, line = raw;

      if (/^### Requirement:/.test(line)) {
        push();
        var title = line.replace(/^### Requirement:\s*/, "").trim();
        var tlow = title.toLowerCase();
        cur = { title: title, line: n, hasNorm: false, seenGiven: false, scenarios: 0,
                // negativity may live in the TITLE, not only in the body
                negative: /(^|[^a-z0-9])only([^a-z0-9]|$)/.test(tlow) ||
                          /(^|[^a-z0-9])never([^a-z0-9]|$)/.test(tlow) ||
                          /must not/.test(tlow) };
        curKind = "req"; reqs.push(cur); inPo = false; return;
      }
      if (/^### Property:/.test(line)) {
        push();
        cur = { name: line.replace(/^### Property:\s*/, "").trim(), line: n, hasGen: false };
        curKind = "prop"; props.push(cur); inPo = false; return;
      }
      if (/^### Temporal:/.test(line)) {
        push();
        cur = { name: line.replace(/^### Temporal:\s*/, "").trim(), line: n, trig: false, resp: false };
        curKind = "temp"; temps.push(cur); inPo = false; return;
      }
      if (/^## /.test(line)) {
        push();
        inPo = /^## Proof Obligations/.test(line);
        if (inPo) hasPo = true;
        return;
      }

      if (curKind === "req" && cur) {
        if (!cur.seenGiven && /\*\*Given\*\*/.test(line)) cur.seenGiven = true;
        if (!cur.seenGiven && /(^|[^A-Za-z])(SHALL|MUST)([^A-Za-z]|$)/.test(line)) cur.hasNorm = true;
        var low = line.toLowerCase();
        if (/(^|[^a-z0-9])only([^a-z0-9]|$)/.test(low) ||
            /(^|[^a-z0-9])never([^a-z0-9]|$)/.test(low) ||
            /must not/.test(low)) cur.negative = true;
        if (/^#### Scenario:/.test(line)) cur.scenarios++;
        if (STOP_VAGUE.test(line))
          out.push({ lvl: "WARN", code: "W1", line: n,
                     msg: 'vague word in requirement "' + cur.title + '": ' + line.trim().slice(0, 78) });
      }
      if (curKind === "prop" && cur && /\*\*Generator strategy\*\*/.test(line)) cur.hasGen = true;
      if (curKind === "temp" && cur) {
        if (/\*\*Trigger event\*\*/.test(line)) cur.trig = true;
        if (/\*\*Response event\*\*/.test(line)) cur.resp = true;
      }

      if (inPo && /^\|/.test(line) && !/^\|[\s:]*-/.test(line) && !/^\|\s*Obligation/.test(line)) {
        poRows++;
        checkSource(line, n);
      }
    });
    push();

    function checkSource(row, lineno) {
      var cells = row.split("|");
      if (cells.length < 4) return;
      var src = (cells[2] || "").trim();
      if (!src || /^<!--/.test(src)) return;
      var hit = false;

      // (1) ordinals: "Requirement 3", "R3"
      var toks = src.split(/[^A-Za-z0-9]+/);
      for (var i = 0; i < toks.length; i++) {
        var idx = 0;
        if (/^R\d+$/.test(toks[i])) idx = parseInt(toks[i].slice(1), 10);
        else if (toks[i] === "Requirement" && /^\d+$/.test(toks[i + 1] || "")) idx = parseInt(toks[i + 1], 10);
        if (idx >= 1 && idx <= reqs.length) { covered[idx] = true; hit = true; ordinalRefs++; }
        else if (idx > reqs.length) {
          out.push({ lvl: "FAIL", code: "F6", line: lineno,
            msg: "Source cites Requirement " + idx + " but the spec has " + reqs.length });
          hit = true;
        }
      }
      // (2) exact title quoted in the Source cell
      var low = src.toLowerCase();
      reqs.forEach(function (r, j) {
        var key = r.title.toLowerCase().slice(0, 40);
        if (key && low.indexOf(key) >= 0) { covered[j + 1] = true; hit = true; titleRefs++; }
      });
      if (hit) return;
      // (3) typed non-requirement source
      if (/(^|[^A-Za-z])(Property|Properties|Scenario|Scenarios|Invariant|Compile-Negative|Temporal|Criterion|Type-Constraint|MUST-CONFIRM|Design|Non-goal)\s*(:|\d)/.test(src) ||
          /^(MUST-CONFIRM|Compile-Negative)([^A-Za-z]|$)/.test(src)) return;
      out.push({ lvl: "FAIL", code: "F6", line: lineno,
        msg: "Source names no resolvable reference: " + src.slice(0, 64) });
    }

    reqs.forEach(function (r) {
      if (!r.hasNorm)
        out.push({ lvl: "FAIL", code: "F1", line: r.line,
          msg: 'requirement "' + r.title + '" has no SHALL/MUST before its first **Given**' });
      if (r.negative && r.scenarios === 0)
        out.push({ lvl: "FAIL", code: "F2", line: r.line,
          msg: 'negative requirement "' + r.title + '" (only/never/must not) has no scenario at all' });
      else if (r.negative)
        out.push({ lvl: "WARN", code: "W3", line: r.line,
          msg: 'requirement "' + r.title + '" is negative — confirm a scenario input is forbidden by it' });
    });
    props.forEach(function (p) {
      if (!p.hasGen)
        out.push({ lvl: "FAIL", code: "F3", line: p.line,
          msg: 'property "' + p.name + '" has no **Generator strategy** line' });
    });
    temps.forEach(function (t) {
      if (!t.trig) out.push({ lvl: "FAIL", code: "F5", line: t.line, msg: 'temporal "' + t.name + '" has no **Trigger event** line' });
      if (!t.resp) out.push({ lvl: "FAIL", code: "F5", line: t.line, msg: 'temporal "' + t.name + '" has no **Response event** line' });
    });
    if (reqs.length && !hasPo)
      out.push({ lvl: "FAIL", code: "F4", line: 0,
        msg: "spec has " + reqs.length + " requirement(s) but no ## Proof Obligations section" });
    if (hasPo && poRows < reqs.length)
      out.push({ lvl: "WARN", code: "W2", line: 0,
        msg: "Proof Obligations has " + poRows + " data row(s) for " + reqs.length + " requirement(s)" });
    if (hasPo)
      reqs.forEach(function (r, j) {
        if (!covered[j + 1])
          out.push({ lvl: "FAIL", code: "F7", line: r.line,
            msg: 'requirement "' + r.title + '" is named by NO proof obligation (unenforced)' });
      });
    if (ordinalRefs > 0 && titleRefs === 0)
      out.push({ lvl: "WARN", code: "W4", line: 0,
        msg: ordinalRefs + " obligation Source(s) reference requirements BY ORDINAL only — reordering silently re-points them; prefer \"Requirement: <exact title>\"" });

    out.sort(function (a, b) { return (a.line || 0) - (b.line || 0); });
    return { findings: out, reqs: reqs.length, props: props.length, poRows: poRows };
  }

  function renderLint(res, host) {
    var fails = res.findings.filter(function (f) { return f.lvl === "FAIL"; }).length;
    var warns = res.findings.length - fails;
    var v = document.createElement("div");
    v.className = "verdict " + (fails ? "fail" : "pass");
    var head = '<div class="vhead">' +
      (fails ? '<span class="tag-fail">FAILED</span> — ' : '<span class="tag-pass">PASS</span> — ') +
      res.reqs + " requirement(s), " + res.props + " propert(ies), " + res.poRows + " obligation row(s): " +
      fails + " FAIL, " + warns + " WARN</div>";
    var list = res.findings.map(function (f) {
      var cls = f.lvl === "FAIL" ? "tag-fail" : "tag-warn";
      return "<li><span class='" + cls + "'>" + f.lvl + " " + f.code + "</span>" +
        (f.line ? " line " + f.line : "") + ": " + escapeHtml(f.msg) + "</li>";
    }).join("");
    v.innerHTML = head + (list ? "<ul>" + list + "</ul>" : "<p style='margin:.5rem 0 0'>No findings — every mechanical check passes.</p>");
    host.innerHTML = "";
    host.appendChild(v);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  function initLinters() {
    document.querySelectorAll("[data-linter]").forEach(function (host) {
      var ta = host.querySelector("textarea.editor");
      var out = host.querySelector(".lint-out");
      var runBtn = host.querySelector("[data-run]");
      var presets = host.querySelectorAll("[data-preset]");
      if (!ta || !out) return;
      function run() { renderLint(lintSpec(ta.value), out); }
      if (runBtn) runBtn.addEventListener("click", run);
      presets.forEach(function (b) {
        b.addEventListener("click", function () {
          var src = document.getElementById(b.getAttribute("data-preset"));
          if (src) { ta.value = src.textContent.trim(); run(); }
        });
      });
      var auto = host.getAttribute("data-linter");
      if (auto && document.getElementById(auto)) {
        var seed = document.getElementById(auto);
        ta.value = seed.textContent.trim();
      }
      run();
    });
    window.vs3LintSpec = lintSpec; // exposed for chapter-specific widgets
  }

  /* ── light syntax colouring for <pre data-lang> ─────────────────────── */
  function highlight() {
    document.querySelectorAll("pre[data-lang]").forEach(function (pre) {
      var lang = pre.getAttribute("data-lang");
      var code = pre.querySelector("code") || pre;
      if (code.dataset.hl) return;
      // never rewrite blocks that carry hand-authored markup (drill hotspots)
      if (code.children.length) { code.dataset.hl = "1"; return; }
      code.dataset.hl = "1";
      var html = escapeHtml(code.textContent);
      if (lang === "scala") {
        html = html.replace(/(&quot;[^&]*?&quot;|"[^"]*")/g, '<span class="s">$1</span>');
        html = html.replace(/\b(def|val|var|case|class|object|trait|enum|sealed|opaque|type|given|using|extension|import|package|new|extends|with|if|else|match|final|private|override|implicit|lazy|return|yield|for|while|abstract)\b/g, '<span class="k">$1</span>');
        html = html.replace(/(\/\/[^\n]*)/g, '<span class="c">$1</span>');
      } else if (lang === "bash") {
        html = html.replace(/(#[^\n]*)/g, '<span class="c">$1</span>');
        html = html.replace(/^(\s*)(\$ )/gm, '$1<span class="c">$2</span>');
      } else if (lang === "md") {
        html = html.replace(/^(#{1,6} .*)$/gm, '<span class="k">$1</span>');
        html = html.replace(/(\*\*[^*]+\*\*)/g, '<span class="t">$1</span>');
        html = html.replace(/(&lt;!--[\s\S]*?--&gt;)/g, '<span class="c">$1</span>');
      }
      code.innerHTML = html;
    });
  }

  /* ── boot ───────────────────────────────────────────────────────────── */
  function boot() {
    initTheme(); initToc(); initTabs(); initQuiz();
    initPipelines(); initDrills(); initLinters(); highlight();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
