/**
 * Google Gemini (Google AI Studio) client.
 * Script Properties: GEMINI_API_KEY, optional GEMINI_MODEL
 * Key: https://aistudio.google.com/apikey
 */

function getGeminiSettings_() {
  var props = PropertiesService.getScriptProperties();
  var model = props.getProperty(PROP_GEMINI_MODEL) || 'gemini-3.0-flash';
  if (
    model === 'gemini-2.0-flash' ||
    model === 'gemini-2.5-flash' ||
    model === 'gemini-2.5-flash-lite'
  ) {
    model = 'gemini-3.0-flash';
    props.setProperty(PROP_GEMINI_MODEL, model);
  }
  return {
    apiKey: props.getProperty(PROP_GEMINI_API_KEY) || '',
    model: model
  };
}

function isGeminiConfigured_() {
  return !!getGeminiSettings_().apiKey;
}

/**
 * @param {string} apiKey
 * @param {string=} model
 */
function saveGeminiSettings(apiKey, model) {
  var props = PropertiesService.getScriptProperties();
  apiKey = String(apiKey || '').trim();
  if (!apiKey) throw new Error('Укажите Gemini API-ключ');
  props.setProperty(PROP_GEMINI_API_KEY, apiKey);
  if (model && String(model).trim()) {
    props.setProperty(PROP_GEMINI_MODEL, String(model).trim());
  }
  return {
    ok: true,
    model: props.getProperty(PROP_GEMINI_MODEL) || 'gemini-3.0-flash'
  };
}

/**
 * Gemini 3 defaults to heavy thinking (slow). Prefer minimal / budget 0.
 * @param {string} model
 * @return {Object}
 */
function geminiThinkingConfig_(model) {
  var m = String(model || '').toLowerCase();
  if (m.indexOf('gemini-3') === 0) {
    return { thinkingLevel: 'minimal' };
  }
  return { thinkingBudget: 0 };
}

function geminiGenerationConfig_(model, schema) {
  var cfg = {
    temperature: 0,
    maxOutputTokens: 512,
    responseMimeType: 'application/json',
    thinkingConfig: geminiThinkingConfig_(model)
  };
  if (schema) cfg.responseSchema = schema;
  return cfg;
}

var FINANCE_JSON_SCHEMA_ = {
  type: 'object',
  properties: {
    type: { type: 'string', nullable: true, enum: ['income', 'expense', null] },
    amount: { type: 'number', nullable: true },
    category: { type: 'string', nullable: true },
    comment: { type: 'string' },
    transcript: { type: 'string', nullable: true },
    ok: { type: 'boolean' },
    error: { type: 'string', nullable: true }
  },
  required: ['type', 'amount', 'category', 'comment', 'ok', 'error']
};

function financeSystemPrompt_(categoryNames) {
  var cats = (categoryNames || []).join(', ') || '(список пуст)';
  return (
    'Учёт личных финансов в рублях. Из заметки (текста или русской речи в аудио) извлеки одну операцию. ' +
    'Снятие наличных, покупка, оплата = expense. Зарплата, перевод себе на карту как поступление = income. ' +
    'Известные категории расходов: ' +
    cats +
    '. ' +
    'category — краткое имя (для expense); для income category=null. ' +
    'comment — краткий смысл без суммы. ' +
    'Если вход — аудио, заполни transcript текстом распознанной фразы. ' +
    'ok=true только если известны type и amount>0.'
  );
}

/**
 * @param {string} systemText
 * @param {Array} userParts Gemini content parts (text and/or inlineData)
 * @param {Object=} schema
 * @return {string}
 */
function geminiCompleteParts_(systemText, userParts, schema) {
  var s = getGeminiSettings_();
  if (!s.apiKey) {
    throw new Error(
      'Gemini не настроен. Меню «Доходы–расходы» → «Настроить Gemini».'
    );
  }

  var model = encodeURIComponent(s.model);
  var url =
    'https://generativelanguage.googleapis.com/v1beta/models/' +
    model +
    ':generateContent?key=' +
    encodeURIComponent(s.apiKey);

  var useSchema = schema === undefined ? FINANCE_JSON_SCHEMA_ : schema;
  var payload = {
    systemInstruction: {
      parts: [{ text: systemText }]
    },
    contents: [
      {
        role: 'user',
        parts: userParts
      }
    ],
    generationConfig: geminiGenerationConfig_(s.model, useSchema)
  };

  var resp = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    payload: JSON.stringify(payload),
    muteHttpExceptions: true
  });

  var code = resp.getResponseCode();
  var body = resp.getContentText();
  if (code < 200 || code >= 300) {
    if (
      code === 400 &&
      /thinkingConfig|thinkingLevel|thinkingBudget|responseSchema|Unknown name/i.test(body)
    ) {
      return geminiCompletePartsRetry_(url, systemText, userParts, useSchema, body);
    }
    throw new Error('Gemini HTTP ' + code + ': ' + body.slice(0, 500));
  }

  return extractGeminiText_(body);
}

/**
 * Retry with the other thinking knob, then without thinkingConfig.
 */
function geminiCompletePartsRetry_(url, systemText, userParts, schema, firstErrorBody) {
  var attempts = [];
  if (/thinkingBudget/i.test(firstErrorBody) || /thinkingLevel/i.test(firstErrorBody)) {
    attempts.push({ thinkingLevel: 'minimal' });
    attempts.push({ thinkingBudget: 0 });
  } else {
    attempts.push({ thinkingBudget: 0 });
    attempts.push({ thinkingLevel: 'minimal' });
  }
  attempts.push(null);

  var lastErr = firstErrorBody;
  for (var i = 0; i < attempts.length; i++) {
    var cfg = {
      temperature: 0,
      maxOutputTokens: 512,
      responseMimeType: 'application/json'
    };
    if (schema && i < attempts.length - 1) cfg.responseSchema = schema;
    if (attempts[i]) cfg.thinkingConfig = attempts[i];

    var payload = {
      systemInstruction: { parts: [{ text: systemText }] },
      contents: [{ role: 'user', parts: userParts }],
      generationConfig: cfg
    };
    var resp = UrlFetchApp.fetch(url, {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
    var code = resp.getResponseCode();
    var body = resp.getContentText();
    if (code >= 200 && code < 300) return extractGeminiText_(body);
    lastErr = body;
    if (code !== 400) break;
  }
  throw new Error('Gemini HTTP 400: ' + String(lastErr).slice(0, 500));
}

function geminiCompletePartsSimple_(url, systemText, userParts) {
  return geminiCompletePartsRetry_(url, systemText, userParts, null, 'thinkingConfig');
}

/**
 * @param {string} systemText
 * @param {string} userText
 * @return {string}
 */
function geminiComplete_(systemText, userText) {
  return geminiCompleteParts_(systemText, [{ text: userText }]);
}

/** Fallback for models that reject schema/thinkingConfig. */
function geminiCompleteSimple_(url, systemText, userText) {
  return geminiCompletePartsSimple_(url, systemText, [{ text: userText }]);
}

function extractGeminiText_(body) {
  var data = JSON.parse(body);
  if (data.error) {
    throw new Error('Gemini: ' + (data.error.message || JSON.stringify(data.error)));
  }

  var cand = data.candidates && data.candidates[0];
  if (!cand) throw new Error('Пустой ответ Gemini (нет candidates)');

  var parts = (cand.content && cand.content.parts) || [];
  var text = parts
    .filter(function (p) {
      return !p.thought;
    })
    .map(function (p) {
      return p.text || '';
    })
    .join('');

  if (!text) {
    throw new Error(
      'Пустой текст Gemini' +
        (cand.finishReason ? ' (' + cand.finishReason + ')' : '')
    );
  }
  return text;
}

/**
 * @param {string} noteText
 * @param {string[]} categoryNames
 * @return {Object}
 */
function geminiParseFinanceNote_(noteText, categoryNames) {
  var raw = geminiComplete_(financeSystemPrompt_(categoryNames), noteText);
  return parseJsonFromLlm_(raw);
}

/**
 * @param {string} base64 audio without data: prefix
 * @param {string} mimeType e.g. audio/webm
 * @param {string[]} categoryNames
 * @return {Object}
 */
function geminiParseFinanceAudio_(base64, mimeType, categoryNames) {
  mimeType = String(mimeType || 'audio/webm').split(';')[0];
  base64 = String(base64 || '').replace(/\s/g, '');
  if (!base64) throw new Error('Пустое аудио');
  if (base64.length > 4 * 1024 * 1024) {
    throw new Error('Аудио слишком длинное. Запишите короче (до ~15 сек).');
  }

  var parts = [
    {
      inlineData: {
        mimeType: mimeType,
        data: base64
      }
    },
    {
      text:
        'Это голосовая заметка на русском. Распознай речь и извлеки финансовую операцию в JSON.'
    }
  ];
  var raw = geminiCompleteParts_(financeSystemPrompt_(categoryNames), parts);
  return parseJsonFromLlm_(raw);
}

function parseJsonFromLlm_(raw) {
  var t = String(raw || '').trim();
  var fence = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) t = fence[1].trim();
  var start = t.indexOf('{');
  var end = t.lastIndexOf('}');
  if (start < 0 || end <= start) {
    throw new Error('Gemini вернул не JSON: ' + t.slice(0, 200));
  }
  try {
    return JSON.parse(t.slice(start, end + 1));
  } catch (err) {
    throw new Error('Gemini вернул битый JSON: ' + t.slice(0, 200));
  }
}

var TODO_JSON_SCHEMA_ = {
  type: 'object',
  properties: {
    text: { type: 'string' },
    dueDate: { type: 'string', nullable: true },
    dueTime: { type: 'string', nullable: true },
    transcript: { type: 'string', nullable: true },
    ok: { type: 'boolean' },
    error: { type: 'string', nullable: true }
  },
  required: ['text', 'dueDate', 'dueTime', 'ok', 'error']
};

function todoSystemPrompt_() {
  var today = todayYmdMoscow_();
  return (
    'Личные напоминания и задачи. Из фразы (текста или русской речи) извлеки одну заметку. ' +
    'Сегодня по Москве: ' +
    today +
    '. ' +
    'text — смысл заметки без даты и времени. ' +
    'dueDate — дата выполнения yyyy-MM-dd или null (завтра, послезавтра, в понедельник → абсолютная дата). ' +
    'dueTime — время HH:mm или null (если не названо). ' +
    'ok=true если text не пустой.'
  );
}

function geminiCompletePartsWithSchema_(systemText, userParts, schema) {
  return geminiCompleteParts_(systemText, userParts, schema);
}

/**
 * @param {string} noteText
 * @return {Object}
 */
function geminiParseTodoNote_(noteText) {
  var raw = geminiCompletePartsWithSchema_(
    todoSystemPrompt_(),
    [{ text: noteText }],
    TODO_JSON_SCHEMA_
  );
  return parseJsonFromLlm_(raw);
}

/**
 * @param {string} base64
 * @param {string} mimeType
 * @return {Object}
 */
function geminiParseTodoAudio_(base64, mimeType) {
  mimeType = String(mimeType || 'audio/webm').split(';')[0];
  base64 = String(base64 || '').replace(/\s/g, '');
  if (!base64) throw new Error('Пустое аудио');
  if (base64.length > 4 * 1024 * 1024) {
    throw new Error('Аудио слишком длинное. Запишите короче (до ~15 сек).');
  }

  var parts = [
    {
      inlineData: {
        mimeType: mimeType,
        data: base64
      }
    },
    {
      text:
        'Это голосовая заметка-напоминание на русском. Распознай речь и извлеки заметку в JSON.'
    }
  ];
  var raw = geminiCompletePartsWithSchema_(
    todoSystemPrompt_(),
    parts,
    TODO_JSON_SCHEMA_
  );
  return parseJsonFromLlm_(raw);
}
