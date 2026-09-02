/**
 * Ensures Transactions and Categories sheets exist with headers.
 * @return {Spreadsheet}
 */
function ensureSheets_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error('Скрипт должен быть привязан к таблице (Расширения → Apps Script).');
  }

  var tx = ss.getSheetByName(SHEET_TRANSACTIONS);
  if (!tx) {
    tx = ss.insertSheet(SHEET_TRANSACTIONS);
    tx.getRange(1, 1, 1, TX_HEADERS.length).setValues([TX_HEADERS]);
    tx.setFrozenRows(1);
  } else if (tx.getLastRow() === 0) {
    tx.getRange(1, 1, 1, TX_HEADERS.length).setValues([TX_HEADERS]);
    tx.setFrozenRows(1);
  }

  var cat = ss.getSheetByName(SHEET_CATEGORIES);
  if (!cat) {
    cat = ss.insertSheet(SHEET_CATEGORIES);
    cat.getRange(1, 1, 1, CAT_HEADERS.length).setValues([CAT_HEADERS]);
    cat.setFrozenRows(1);
    seedDefaultCategories_(cat);
  } else if (cat.getLastRow() === 0) {
    cat.getRange(1, 1, 1, CAT_HEADERS.length).setValues([CAT_HEADERS]);
    cat.setFrozenRows(1);
    seedDefaultCategories_(cat);
  }

  return ss;
}

function seedDefaultCategories_(sheet) {
  var now = new Date();
  var rows = DEFAULT_CATEGORIES.map(function (name) {
    return [Utilities.getUuid(), name, now];
  });
  if (rows.length) {
    sheet.getRange(2, 1, rows.length, CAT_HEADERS.length).setValues(rows);
  }
}

function getTxSheet_() {
  ensureSheets_();
  return SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_TRANSACTIONS);
}

function getCatSheet_() {
  ensureSheets_();
  return SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_CATEGORIES);
}

function newId_() {
  return Utilities.getUuid();
}

/**
 * @param {Date|string|number} value
 * @return {string} ISO-like local string for client
 */
function toIso_(value) {
  if (!value) return '';
  var d = value instanceof Date ? value : new Date(value);
  if (isNaN(d.getTime())) return '';
  return Utilities.formatDate(d, Session.getScriptTimeZone(), "yyyy-MM-dd'T'HH:mm:ss");
}

/**
 * @param {string} isoOrDate
 * @return {Date}
 */
function parseDateInput_(isoOrDate) {
  if (!isoOrDate) return new Date();
  if (isoOrDate instanceof Date) return isoOrDate;
  var d = new Date(isoOrDate);
  if (isNaN(d.getTime())) {
    throw new Error('Некорректная дата: ' + isoOrDate);
  }
  return d;
}

/**
 * Start/end of local calendar day in script timezone.
 * @param {string} ymd yyyy-MM-dd
 * @param {boolean} endOfDay
 * @return {Date}
 */
function parseYmdBound_(ymd, endOfDay) {
  var tz = Session.getScriptTimeZone();
  var parts = String(ymd).split('-');
  if (parts.length !== 3) throw new Error('Ожидается дата yyyy-MM-dd');
  var y = Number(parts[0]);
  var m = Number(parts[1]);
  var day = Number(parts[2]);
  var h = endOfDay ? 23 : 0;
  var min = endOfDay ? 59 : 0;
  var s = endOfDay ? 59 : 0;
  var str =
    y +
    '-' +
    ('0' + m).slice(-2) +
    '-' +
    ('0' + day).slice(-2) +
    ' ' +
    ('0' + h).slice(-2) +
    ':' +
    ('0' + min).slice(-2) +
    ':' +
    ('0' + s).slice(-2);
  return Utilities.parseDate(str, tz, 'yyyy-MM-dd HH:mm:ss');
}
