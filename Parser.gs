/**
 * Parses free-text finance note via Google Gemini.
 * @param {string} text
 * @return {Object}
 */
function parseNote(text) {
  text = String(text || '').trim();
  if (!text) {
    return { ok: false, error: 'Пустая заметка.', draft: null };
  }

  if (!isGeminiConfigured_()) {
    return {
      ok: false,
      error:
        'Сначала настройте Gemini: меню таблицы «Доходы–расходы» → «Настроить Gemini».',
      draft: {
        type: null,
        amount: null,
        category: null,
        categoryIsNew: false,
        comment: '',
        rawText: text
      }
    };
  }

  var names = listCategoryNamesCached_();

  try {
    var ai = geminiParseFinanceNote_(text, names);
    return normalizeAiDraft_(ai, text, names);
  } catch (err) {
    return {
      ok: false,
      error: 'Ошибка Gemini: ' + (err.message || String(err)),
      draft: {
        type: null,
        amount: null,
        category: null,
        categoryIsNew: false,
        comment: '',
        rawText: text
      }
    };
  }
}

/**
 * Voice note: audio → Gemini (STT + parse).
 * @param {string} base64
 * @param {string} mimeType
 */
function parseAudioNote(base64, mimeType) {
  if (!isGeminiConfigured_()) {
    return {
      ok: false,
      error:
        'Сначала настройте Gemini: меню таблицы «Доходы–расходы» → «Настроить Gemini».',
      draft: null
    };
  }

  var names = listCategoryNamesCached_();

  try {
    var ai = geminiParseFinanceAudio_(base64, mimeType, names);
    var transcript =
      (ai && ai.transcript && String(ai.transcript).trim()) ||
      (ai && ai.comment) ||
      '[голос]';
    return normalizeAiDraft_(ai, transcript, names);
  } catch (err) {
    return {
      ok: false,
      error: 'Ошибка Gemini (голос): ' + (err.message || String(err)),
      draft: {
        type: null,
        amount: null,
        category: null,
        categoryIsNew: false,
        comment: '',
        rawText: ''
      }
    };
  }
}

/**
 * @param {Object} ai
 * @param {string} text
 * @param {string[]} knownNames
 */
function normalizeAiDraft_(ai, text, knownNames) {
  ai = ai || {};
  var type = ai.type === 'income' || ai.type === 'expense' ? ai.type : null;
  var amount = ai.amount != null && ai.amount !== '' ? Number(ai.amount) : null;
  if (!(amount > 0)) amount = null;

  var category = null;
  var categoryIsNew = false;
  if (type === 'expense') {
    category = ai.category ? String(ai.category).trim() : '';
    if (!category) {
      category = null;
    } else {
      var known = null;
      for (var i = 0; i < knownNames.length; i++) {
        if (knownNames[i].toLowerCase() === category.toLowerCase()) {
          known = knownNames[i];
          break;
        }
      }
      if (known) {
        category = known;
        categoryIsNew = false;
      } else {
        category = category.charAt(0).toUpperCase() + category.slice(1);
        categoryIsNew = true;
      }
    }
  }

  var comment = ai.comment != null ? String(ai.comment).trim() : '';
  var draft = {
    type: type,
    amount: amount,
    category: category,
    categoryIsNew: categoryIsNew,
    comment: comment,
    rawText: text
  };

  var missing = [];
  if (amount == null) missing.push('amount');
  if (!type) missing.push('type');

  var ok = ai.ok === true && !missing.length;
  if (!ok && missing.length) {
    return {
      ok: false,
      error: missingMessage_(missing),
      missing: missing,
      draft: draft
    };
  }
  if (!ok && ai.error) {
    return { ok: false, error: String(ai.error), draft: draft };
  }
  return { ok: true, draft: draft };
}

function missingMessage_(missing) {
  var map = {
    amount: 'сумму',
    type: 'тип (доход или расход)',
    category: 'категорию расхода'
  };
  var parts = missing.map(function (m) {
    return map[m] || m;
  });
  return 'Не хватает: ' + parts.join(', ') + '. Заполните в форме.';
}
