/**
 * Parses todo notes (text / audio) via Gemini.
 */

function todayYmdMoscow_() {
  return Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function parseTodoNote(text) {
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
        text: text,
        dueDate: '',
        dueTime: '',
        rawText: text
      }
    };
  }

  try {
    var ai = geminiParseTodoNote_(text);
    return normalizeTodoDraft_(ai, text);
  } catch (err) {
    return {
      ok: false,
      error: 'Ошибка Gemini: ' + (err.message || String(err)),
      draft: {
        text: text,
        dueDate: '',
        dueTime: '',
        rawText: text
      }
    };
  }
}

function parseTodoAudioNote(base64, mimeType) {
  if (!isGeminiConfigured_()) {
    return {
      ok: false,
      error:
        'Сначала настройте Gemini: меню таблицы «Доходы–расходы» → «Настроить Gemini».',
      draft: null
    };
  }

  try {
    var ai = geminiParseTodoAudio_(base64, mimeType);
    var transcript =
      (ai && ai.transcript && String(ai.transcript).trim()) ||
      (ai && ai.text && String(ai.text).trim()) ||
      '[голос]';
    return normalizeTodoDraft_(ai, transcript);
  } catch (err) {
    return {
      ok: false,
      error: 'Ошибка Gemini (голос): ' + (err.message || String(err)),
      draft: {
        text: '',
        dueDate: '',
        dueTime: '',
        rawText: ''
      }
    };
  }
}

function normalizeTodoDraft_(ai, rawText) {
  ai = ai || {};
  var text = ai.text != null ? String(ai.text).trim() : '';
  if (!text) text = String(rawText || '').trim();

  var dueDate = ai.dueDate != null ? String(ai.dueDate).trim() : '';
  if (dueDate === 'null' || dueDate === 'undefined') dueDate = '';
  if (dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) dueDate = '';

  var dueTime = ai.dueTime != null ? String(ai.dueTime).trim() : '';
  if (dueTime === 'null' || dueTime === 'undefined') dueTime = '';
  if (dueTime) {
    try {
      dueTime = parseDueTimeInput_(dueTime);
    } catch (ignore) {
      dueTime = '';
    }
  }

  var draft = {
    text: text,
    dueDate: dueDate,
    dueTime: dueTime,
    rawText: rawText
  };

  if (!text) {
    return {
      ok: false,
      error: 'Не удалось извлечь текст заметки.',
      draft: draft
    };
  }

  return { ok: true, draft: draft };
}
