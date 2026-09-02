/**
 * Mobile / Android API via doPost (+ ping via doGet?token=&action=ping).
 * URL: https://script.google.com/macros/s/DEPLOY_ID/exec?token=MOBILE_API_TOKEN
 *
 * Actions (JSON body.action):
 *   ping | parseAudio | parseText | create | update | delete
 *   dashboard | getTransaction
 *   listCategories | addCategory | renameCategory | deleteCategory | categoryUsage
 */

function doPost(e) {
  try {
    if (!verifyMobileToken_(e)) {
      return jsonOut_({ ok: false, error: 'Неверный token' });
    }
    ensureSheets_();

    var body = {};
    if (e.postData && e.postData.contents) {
      body = JSON.parse(e.postData.contents);
    }
    var action = body.action || '';

    if (action === 'ping') {
      return jsonOut_({ ok: true, pong: true, gemini: isGeminiConfigured_() });
    }

    if (action === 'parseAudio') {
      return jsonOut_(parseAudioNote(body.audioBase64, body.mimeType || 'audio/mp4'));
    }

    if (action === 'parseText') {
      return jsonOut_(parseNote(body.text || ''));
    }

    if (action === 'create') {
      var draft = body.draft || {};
      var tx = createTransaction({
        type: draft.type,
        amount: draft.amount,
        category: draft.category,
        comment: draft.comment,
        rawText: draft.rawText,
        date: draft.date,
        source: body.source || 'android_voice',
        confirmNewCategory: !!body.confirmNewCategory
      });
      return jsonOut_({ ok: true, transaction: tx });
    }

    if (action === 'update') {
      var upd = body.draft || body.payload || {};
      var updated = updateTransaction(body.id, {
        type: upd.type,
        amount: upd.amount,
        category: upd.category,
        comment: upd.comment,
        date: upd.date,
        confirmNewCategory: !!body.confirmNewCategory
      });
      return jsonOut_({ ok: true, transaction: updated });
    }

    if (action === 'delete') {
      return jsonOut_(deleteTransaction(body.id));
    }

    if (action === 'dashboard') {
      var dash = getDashboard({
        from: body.from || '',
        to: body.to || ''
      });
      return jsonOut_({ ok: true, dashboard: dash });
    }

    if (action === 'getTransaction') {
      return jsonOut_({ ok: true, transaction: getTransaction(body.id) });
    }

    if (action === 'listCategories') {
      return jsonOut_({ ok: true, categories: listCategories() });
    }

    if (action === 'addCategory') {
      return jsonOut_({ ok: true, category: addCategory(body.name) });
    }

    if (action === 'renameCategory') {
      return jsonOut_({
        ok: true,
        category: renameCategory(body.id, body.name || body.newName)
      });
    }

    if (action === 'deleteCategory') {
      return jsonOut_(
        deleteCategory(body.id, body.replacementName || body.replacement || '')
      );
    }

    if (action === 'categoryUsage') {
      return jsonOut_({ ok: true, usage: getCategoryUsage(body.id) });
    }

    return jsonOut_({ ok: false, error: 'Неизвестное action: ' + action });
  } catch (err) {
    return jsonOut_({ ok: false, error: err.message || String(err) });
  }
}

function verifyMobileToken_(e) {
  var expected = PropertiesService.getScriptProperties().getProperty(PROP_MOBILE_TOKEN);
  if (!expected) return false;
  var got = (e && e.parameter && e.parameter.token) || '';
  if (!got && e && e.postData && e.postData.contents) {
    try {
      var body = JSON.parse(e.postData.contents);
      if (body && body.token) got = String(body.token);
    } catch (ignore) {}
  }
  return got === expected;
}

function jsonOut_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(
    ContentService.MimeType.JSON
  );
}

/** Ensure mobile token exists; show in UI. */
function showMobileToken() {
  ensureMobileToken_();
  var token = PropertiesService.getScriptProperties().getProperty(PROP_MOBILE_TOKEN);
  var ui = SpreadsheetApp.getUi();
  ui.alert(
    'Токен для Android',
    'MOBILE_API_TOKEN:\n' +
      token +
      '\n\nВ приложении укажите URL веб-приложения (.../exec) и этот token.\n' +
      'Деплой: Execute as Me, Who has access: Anyone.',
    ui.ButtonSet.OK
  );
  Logger.log('MOBILE_API_TOKEN=' + token);
}

function ensureMobileToken_() {
  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty(PROP_MOBILE_TOKEN)) {
    props.setProperty(PROP_MOBILE_TOKEN, Utilities.getUuid().replace(/-/g, ''));
  }
}
