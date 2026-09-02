/**
 * Web entry + client API.
 * Web UI: doGet checks owner.
 * Android: doPost + ?token= (Anyone access on deployment).
 */

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('Доходы–расходы')
    .addItem('Первичная настройка (setup)', 'setup')
    .addItem('Настроить Gemini…', 'setupGemini')
    .addItem('Проверить Gemini', 'testGemini')
    .addItem('Токен для Android…', 'showMobileToken')
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
    ).setTitle('Доходы–расходы');
  }

  return HtmlService.createTemplateFromFile('Index')
    .evaluate()
    .setTitle('Доходы–расходы')
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

function setup() {
  ensureSheets_();
  rememberOwnerEmail_();
  ensureMobileToken_();
  Logger.log('Owner: ' + PropertiesService.getScriptProperties().getProperty(PROP_OWNER_EMAIL));
  Logger.log('Gemini configured: ' + isGeminiConfigured_());
  Logger.log('MOBILE_API_TOKEN: ' + PropertiesService.getScriptProperties().getProperty(PROP_MOBILE_TOKEN));

  try {
    var ui = SpreadsheetApp.getUi();
    var msg =
      'Листы готовы.\nВладелец: ' +
      (PropertiesService.getScriptProperties().getProperty(PROP_OWNER_EMAIL) || '—') +
      '\nGemini: ' +
      (isGeminiConfigured_() ? 'настроен' : 'не настроен — «Настроить Gemini»') +
      '\nAndroid token: создан (меню «Токен для Android»).';
    ui.alert('Настройка выполнена', msg, ui.ButtonSet.OK);
  } catch (err) {
    // editor without UI
  }
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
