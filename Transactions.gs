/**
 * Row → object
 * @param {Array} r
 */
function txFromRow_(r) {
  return {
    id: String(r[0]),
    createdAt: toIso_(r[1]),
    date: toIso_(r[2]),
    type: String(r[3]),
    amount: Number(r[4]),
    category: r[5] ? String(r[5]) : '',
    comment: r[6] ? String(r[6]) : '',
    source: r[7] ? String(r[7]) : '',
    rawText: r[8] ? String(r[8]) : ''
  };
}

/**
 * @param {{from?:string,to?:string}} filter from/to as yyyy-MM-dd
 * @return {{balance:number,transactions:Array,periodIncome:number,periodExpense:number}}
 */
function getDashboard(filter) {
  ensureSheets_();
  filter = filter || {};
  var all = listAllTransactions_();
  var balance = 0;
  for (var i = 0; i < all.length; i++) {
    if (all[i].type === 'income') balance += all[i].amount;
    else if (all[i].type === 'expense') balance -= all[i].amount;
  }

  var fromDate = filter.from ? parseYmdBound_(filter.from, false) : null;
  var toDate = filter.to ? parseYmdBound_(filter.to, true) : null;

  var filtered = all.filter(function (t) {
    var d = new Date(t.date);
    if (fromDate && d < fromDate) return false;
    if (toDate && d > toDate) return false;
    return true;
  });

  filtered.sort(function (a, b) {
    return new Date(b.date) - new Date(a.date);
  });

  var periodIncome = 0;
  var periodExpense = 0;
  for (var j = 0; j < filtered.length; j++) {
    if (filtered[j].type === 'income') periodIncome += filtered[j].amount;
    else periodExpense += filtered[j].amount;
  }

  return {
    balance: roundMoney_(balance),
    periodIncome: roundMoney_(periodIncome),
    periodExpense: roundMoney_(periodExpense),
    transactions: filtered,
    categories: listCategories(),
    timeZone: Session.getScriptTimeZone()
  };
}

function listAllTransactions_() {
  var sheet = getTxSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return [];
  var values = sheet.getRange(2, 1, last, TX_HEADERS.length).getValues();
  return values
    .filter(function (r) {
      return r[0];
    })
    .map(txFromRow_);
}

function roundMoney_(n) {
  return Math.round(Number(n) * 100) / 100;
}

/**
 * @param {Object} payload
 * @return {Object} saved transaction
 */
function createTransaction(payload) {
  payload = payload || {};
  var type = payload.type;
  if (type !== 'income' && type !== 'expense') {
    throw new Error('Укажите тип: доход или расход');
  }
  var amount = Number(payload.amount);
  if (!(amount > 0)) throw new Error('Сумма должна быть больше нуля');

  var category = '';
  if (type === 'expense') {
    category = String(payload.category || '').trim();
    if (!category) throw new Error('Для расхода укажите категорию');
    if (payload.confirmNewCategory) {
      category = ensureCategoryExists_(category);
    } else {
      var existing = findCategoryByName_(category);
      if (!existing) {
        throw new Error('Новая категория требует подтверждения: ' + category);
      }
      category = existing.name;
    }
  }

  var now = new Date();
  var date = payload.date ? parseDateInput_(payload.date) : now;
  var source = payload.source || 'manual';
  var comment = payload.comment != null ? String(payload.comment) : '';
  var rawText = payload.rawText != null ? String(payload.rawText) : '';

  var id = newId_();
  var row = [id, now, date, type, roundMoney_(amount), category, comment, source, rawText];
  getTxSheet_().appendRow(row);
  return txFromRow_(row);
}

/**
 * @param {string} id
 * @param {Object} payload
 */
function updateTransaction(id, payload) {
  payload = payload || {};
  var sheet = getTxSheet_();
  var found = findTxRow_(id);
  if (!found) throw new Error('Запись не найдена');

  var type = payload.type != null ? payload.type : found.data.type;
  if (type !== 'income' && type !== 'expense') {
    throw new Error('Укажите тип: доход или расход');
  }
  var amount = payload.amount != null ? Number(payload.amount) : found.data.amount;
  if (!(amount > 0)) throw new Error('Сумма должна быть больше нуля');

  var category = '';
  if (type === 'expense') {
    category =
      payload.category != null ? String(payload.category).trim() : found.data.category;
    if (!category) throw new Error('Для расхода укажите категорию');
    if (payload.confirmNewCategory) {
      category = ensureCategoryExists_(category);
    } else {
      var existing = findCategoryByName_(category);
      if (!existing) {
        throw new Error('Новая категория требует подтверждения: ' + category);
      }
      category = existing.name;
    }
  }

  var date =
    payload.date != null ? parseDateInput_(payload.date) : parseDateInput_(found.data.date);
  var comment =
    payload.comment != null ? String(payload.comment) : found.data.comment;

  var row = found.rowIndex;
  // Per-cell writes: setValues on a multi-cell range fails if the sheet has merges.
  sheet.getRange(row, 3).setValue(date);
  sheet.getRange(row, 4).setValue(type);
  sheet.getRange(row, 5).setValue(roundMoney_(amount));
  sheet.getRange(row, 6).setValue(category);
  sheet.getRange(row, 7).setValue(comment);

  return getTransaction(id);
}

function deleteTransaction(id) {
  var found = findTxRow_(id);
  if (!found) throw new Error('Запись не найдена');
  getTxSheet_().deleteRow(found.rowIndex);
  return { ok: true, id: id };
}

function getTransaction(id) {
  var found = findTxRow_(id);
  if (!found) throw new Error('Запись не найдена');
  return found.data;
}

/**
 * @return {{rowIndex:number,data:Object}|null}
 */
function findTxRow_(id) {
  var sheet = getTxSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return null;
  var values = sheet.getRange(2, 1, last, TX_HEADERS.length).getValues();
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]) === String(id)) {
      return { rowIndex: i + 2, data: txFromRow_(values[i]) };
    }
  }
  return null;
}
