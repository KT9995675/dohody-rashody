/**
 * Web entry + client API.
 * Web UI: doGet checks owner.
 * Android: doPost + ?token= (Anyone access on deployment).
 */

/**
 * Browser-tab favicon for GAS Web App (outer shell). Must be a public https URL —
 * data-URI &lt;link&gt; inside the iframe does not change the tab icon.
 * Prefer Drive URL from getFaviconUrl_(); bump ?v= on GitHub fallback when replacing.
 */
function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('Доходы–расходы')
    .addItem('Первичная настройка (setup)', 'setup')
    .addItem('Настроить Gemini…', 'setupGemini')
    .addItem('Проверить Gemini', 'testGemini')
    .addItem('Токен для Android…', 'showMobileToken')
    .addItem('Опубликовать фавикон…', 'publishFavicon')
    .addItem('Открыть веб-приложение…', 'showWebAppHint')
    .addToUi();
}

function showWebAppHint() {
  SpreadsheetApp.getUi().alert(
    'Веб-приложение',
    'Deploy → Web app.\n' +
      'Execute as: Me\n' +
      'Who has access: Anyone (нужно для Android; веб-UI всё равно только владельцу).\n' +
      'Скопируйте URL .../exec в приложение вместе с токеном (меню «Токен для Android»).',
    SpreadsheetApp.getUi().ButtonSet.OK
  );
}

function setupGemini() {
  var ui = SpreadsheetApp.getUi();
  var cur = getGeminiSettings_();
  var keyResp = ui.prompt(
    'Gemini — API-ключ',
    'Ключ из https://aistudio.google.com/apikey\n' +
      (cur.apiKey
        ? 'Сейчас ключ задан (…' + cur.apiKey.slice(-4) + '). Введите новый или оставьте пустым.'
        : 'Вставьте ключ:'),
    ui.ButtonSet.OK_CANCEL
  );
  if (keyResp.getSelectedButton() !== ui.Button.OK) return;

  var modelResp = ui.prompt(
    'Gemini — модель',
    'Имя модели (по умолчанию gemini-3.0-flash).\nСейчас: ' + cur.model,
    ui.ButtonSet.OK_CANCEL
  );
  if (modelResp.getSelectedButton() !== ui.Button.OK) return;

  var apiKey = keyResp.getResponseText().trim() || cur.apiKey;
  var model = modelResp.getResponseText().trim() || 'gemini-3.0-flash';
  try {
    saveGeminiSettings(apiKey, model);
    ui.alert('Сохранено', 'Gemini настроен. Можно нажать «Проверить Gemini».', ui.ButtonSet.OK);
  } catch (err) {
    ui.alert('Ошибка', err.message || String(err), ui.ButtonSet.OK);
  }
}

function testGemini() {
  var ui = SpreadsheetApp.getUi();
  try {
    var r = parseNote('потратил 150 на еду тестовая проверка');
    ui.alert(
      'Проверка Gemini',
      r.ok
        ? 'OK\n' + JSON.stringify(r.draft, null, 2)
        : (r.error || 'не ok') + '\n' + JSON.stringify(r.draft, null, 2),
      ui.ButtonSet.OK
    );
  } catch (err) {
    ui.alert('Ошибка', err.message || String(err), ui.ButtonSet.OK);
  }
}

function doGet(e) {
  // Android: token в query — до проверки владельца (иначе анонимный GET всегда HTML)
  if (e && e.parameter && e.parameter.token) {
    if (!verifyMobileToken_(e)) {
      return jsonOut_({ ok: false, error: 'Неверный token' });
    }
    ensureSheets_();
    var action = e.parameter.action || 'ping';
    if (action === 'ping') {
      return jsonOut_({ ok: true, pong: true, gemini: isGeminiConfigured_() });
    }
    return jsonOut_({ ok: false, error: 'Для action=' + action + ' используйте POST' });
  }

  ensureSheets_();
  rememberOwnerEmail_();

  if (!isOwnerUser_()) {
    return HtmlService.createHtmlOutput(
      '<!DOCTYPE html><html><body style="font-family:sans-serif;padding:2rem">' +
        '<h1>Доступ запрещён</h1>' +
        '<p>Войдите в Google под аккаунтом владельца таблицы.</p>' +
        '</body></html>'
    )
      .setTitle('Доходы–расходы')
      .setFaviconUrl(getFaviconUrl_());
  }

  return HtmlService.createTemplateFromFile('Index')
    .evaluate()
    .setTitle('Доходы–расходы')
    .setFaviconUrl(getFaviconUrl_())
    .addMetaTag('viewport', 'width=device-width, initial-scale=1')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function include(filename) {
  return HtmlService.createHtmlOutputFromFile(filename).getContent();
}

function rememberOwnerEmail_() {
  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty(PROP_OWNER_EMAIL)) {
    var email = Session.getEffectiveUser().getEmail();
    if (email) props.setProperty(PROP_OWNER_EMAIL, email);
  }
}

/** Force web owner to the user running setup (needed after Make a copy). */
function setOwnerToCurrentUser_() {
  var email = '';
  try {
    email = Session.getActiveUser().getEmail() || '';
  } catch (ignore) {}
  if (!email) email = Session.getEffectiveUser().getEmail() || '';
  if (!email) throw new Error('Не удалось определить Google-аккаунт');
  PropertiesService.getScriptProperties().setProperty(PROP_OWNER_EMAIL, email);
  return email;
}

function isOwnerUser_() {
  var active = '';
  try {
    active = Session.getActiveUser().getEmail() || '';
  } catch (err) {
    active = '';
  }
  var effective = Session.getEffectiveUser().getEmail() || '';
  var stored = PropertiesService.getScriptProperties().getProperty(PROP_OWNER_EMAIL) || '';

  if (!active) return false;
  if (stored && active.toLowerCase() === stored.toLowerCase()) return true;
  if (effective && active.toLowerCase() === effective.toLowerCase()) return true;
  return false;
}

/** Deployed web app URL, or empty if not deployed yet. */
function getDeployedWebAppUrl_() {
  try {
    return ScriptApp.getService().getUrl() || '';
  } catch (err) {
    return '';
  }
}

/**
 * Wizard: sheets → owner → token → Gemini → optional wipe → summary.
 * Menu: Доходы–расходы → Первичная настройка (setup)
 */
function setup() {
  var ui;
  try {
    ui = SpreadsheetApp.getUi();
  } catch (err) {
    ensureSheets_();
    ensureDefaultCategoriesIfEmpty_();
    rememberOwnerEmail_();
    ensureMobileToken_();
    return;
  }

  var props = PropertiesService.getScriptProperties();
  var log = [];

  // 1. Sheets
  ensureSheets_();
  ensureDefaultCategoriesIfEmpty_();
  log.push('Листы: Transactions, Categories, Notes — готовы');

  // 2. Owner
  var storedOwner = props.getProperty(PROP_OWNER_EMAIL) || '';
  var currentEmail = '';
  try {
    currentEmail = Session.getActiveUser().getEmail() || '';
  } catch (ignore) {}
  if (!currentEmail) currentEmail = Session.getEffectiveUser().getEmail() || '';

  var ownerQ = ui.alert(
    'Setup — владелец веба',
    'Владелец может открывать веб-UI.\n' +
      'Сейчас в свойствах: ' +
      (storedOwner || '—') +
      '\nВаш аккаунт: ' +
      (currentEmail || '—') +
      '\n\nСделать владельцем текущего пользователя?',
    ui.ButtonSet.YES_NO
  );
  if (ownerQ === ui.Button.YES) {
    try {
      var owner = setOwnerToCurrentUser_();
      log.push('Владелец: ' + owner);
    } catch (err) {
      rememberOwnerEmail_();
      log.push('Владелец: ошибка — ' + (err.message || String(err)));
    }
  } else {
    rememberOwnerEmail_();
    log.push('Владелец: ' + (props.getProperty(PROP_OWNER_EMAIL) || '—') + ' (без изменений)');
  }

  // 3. Android token
  var hadToken = !!props.getProperty(PROP_MOBILE_TOKEN);
  var tokenQ = ui.alert(
    'Setup — токен Android',
    hadToken
      ? 'Токен уже есть. Перевыпустить новый?\n(старый перестанет работать в приложениях)'
      : 'Создать токен для Android?',
    ui.ButtonSet.YES_NO
  );
  if (tokenQ === ui.Button.YES) {
    if (hadToken) reissueMobileToken_();
    else ensureMobileToken_();
    log.push('Android token: ' + (hadToken ? 'перевыпущен' : 'создан'));
  } else {
    ensureMobileToken_();
    log.push('Android token: ' + (hadToken ? 'без изменений' : 'создан'));
  }

  // 4. Gemini
  var cur = getGeminiSettings_();
  if (!cur.apiKey) {
    var keyResp = ui.prompt(
      'Setup — Gemini',
      'Ключ из https://aistudio.google.com/apikey\n' +
        'Вставьте API-ключ (или Отмена — пропустить):',
      ui.ButtonSet.OK_CANCEL
    );
    if (keyResp.getSelectedButton() === ui.Button.OK) {
      var key = keyResp.getResponseText().trim();
      if (key) {
        try {
          saveGeminiSettings(key, 'gemini-3.0-flash');
          log.push('Gemini: ключ сохранён');
        } catch (err) {
          log.push('Gemini: ошибка — ' + (err.message || String(err)));
        }
      } else {
        log.push('Gemini: пропущен (пустой ввод)');
      }
    } else {
      log.push('Gemini: пропущен');
    }
  } else {
    var gemQ = ui.alert(
      'Setup — Gemini',
      'Ключ уже задан (…' +
        cur.apiKey.slice(-4) +
        '), модель: ' +
        cur.model +
        '.\nЗаменить ключ?',
      ui.ButtonSet.YES_NO
    );
    if (gemQ === ui.Button.YES) {
      setupGemini();
      log.push(
        'Gemini: ' + (isGeminiConfigured_() ? 'обновлён (' + getGeminiSettings_().model + ')' : 'не настроен')
      );
    } else {
      log.push('Gemini: без изменений (' + cur.model + ')');
    }
  }

  if (isGeminiConfigured_()) {
    var testQ = ui.alert(
      'Setup — проверка Gemini',
      'Запустить короткий тестовый разбор фразы?',
      ui.ButtonSet.YES_NO
    );
    if (testQ === ui.Button.YES) {
      try {
        var r = parseNote('потратил 150 на еду тестовая проверка');
        log.push(r.ok ? 'Gemini тест: OK' : 'Gemini тест: ' + (r.error || 'не ok'));
      } catch (err) {
        log.push('Gemini тест: ' + (err.message || String(err)));
      }
    }
  }

  // 5. Optional wipe
  var wipeQ = ui.alert(
    'Setup — данные',
    'Очистить записи (транзакции и заметки) и сбросить категории к стандартным?\n' +
      'Заголовки листов сохранятся.',
    ui.ButtonSet.YES_NO
  );
  if (wipeQ === ui.Button.YES) {
    var confirmWipe = ui.alert(
      'Подтверждение',
      'Удалить все транзакции и заметки безвозвратно?',
      ui.ButtonSet.YES_NO
    );
    if (confirmWipe === ui.Button.YES) {
      var wiped = clearAppDataSheets_();
      log.push(
        'Данные очищены (tx: ' +
          wiped.transactions +
          ', notes: ' +
          wiped.notes +
          '; категории сброшены)'
      );
    } else {
      log.push('Очистка данных: отменена');
    }
  } else {
    log.push('Очистка данных: пропущена');
  }

  // 6. Summary
  var token = props.getProperty(PROP_MOBILE_TOKEN) || '—';
  var webUrl = getDeployedWebAppUrl_();
  var lines = [];
  lines.push(log.join('\n'));
  lines.push('');
  lines.push('— Для Android —');
  if (webUrl) {
    lines.push('URL:\n' + webUrl);
  } else {
    lines.push(
      'URL: ещё нет деплоя.\n' +
        'Сделайте вручную: Deploy → Web app\n' +
        'Execute as: Me\n' +
        'Who has access: Anyone\n' +
        'Затем снова Setup или меню «Токен для Android».'
    );
  }
  lines.push('Token:\n' + token);
  lines.push('');
  lines.push(
    'Gemini: ' +
      (isGeminiConfigured_() ? 'ок' : 'нужен ключ') +
      ' · Владелец: ' +
      (props.getProperty(PROP_OWNER_EMAIL) || '—')
  );

  Logger.log(lines.join('\n'));
  ui.alert('Setup завершён', lines.join('\n'), ui.ButtonSet.OK);
}

function assertOwner_() {
  rememberOwnerEmail_();
  if (!isOwnerUser_()) {
    throw new Error('Доступ только для владельца');
  }
}

function apiGetDashboard(filter) {
  assertOwner_();
  return getDashboard(filter || {});
}

function apiParseNote(text) {
  assertOwner_();
  return parseNote(text);
}

function apiParseAudioNote(base64, mimeType) {
  assertOwner_();
  return parseAudioNote(base64, mimeType);
}

function apiCreateTransaction(payload) {
  assertOwner_();
  return createTransaction(payload);
}

function apiUpdateTransaction(id, payload) {
  assertOwner_();
  return updateTransaction(id, payload);
}

function apiDeleteTransaction(id) {
  assertOwner_();
  return deleteTransaction(id);
}

function apiGetTransaction(id) {
  assertOwner_();
  return getTransaction(id);
}

function apiListCategories() {
  assertOwner_();
  return listCategories();
}

function apiAddCategory(name) {
  assertOwner_();
  return addCategory(name);
}

function apiRenameCategory(id, newName) {
  assertOwner_();
  return renameCategory(id, newName);
}

function apiDeleteCategory(id, replacementName) {
  assertOwner_();
  return deleteCategory(id, replacementName);
}

function apiGetCategoryUsage(id) {
  assertOwner_();
  return getCategoryUsage(id);
}

function apiGeminiStatus() {
  assertOwner_();
  var s = getGeminiSettings_();
  return {
    configured: isGeminiConfigured_(),
    model: s.model || 'gemini-3.0-flash',
    hasApiKey: !!s.apiKey
  };
}

/** Full snapshot for web/Android cold start. */
function apiBootstrap() {
  assertOwner_();
  ensureSheets_();
  var dash = getDashboard({ from: '', to: '' });
  return {
    ok: true,
    dashboard: dash,
    notes: listNotes({ includeDone: true }),
    categories: dash.categories || listCategories(),
    timeZone: Session.getScriptTimeZone()
  };
}

function apiParseTodoNote(text) {
  assertOwner_();
  return parseTodoNote(text);
}

function apiParseTodoAudioNote(base64, mimeType) {
  assertOwner_();
  return parseTodoAudioNote(base64, mimeType);
}

function apiListNotes(includeDone) {
  assertOwner_();
  return listNotes({ includeDone: !!includeDone });
}

function apiGetNote(id) {
  assertOwner_();
  return getNote(id);
}

function apiCreateNote(payload) {
  assertOwner_();
  return createNote(payload);
}

function apiUpdateNote(id, payload) {
  assertOwner_();
  return updateNote(id, payload);
}

function apiDeleteNote(id) {
  assertOwner_();
  return deleteNote(id);
}
