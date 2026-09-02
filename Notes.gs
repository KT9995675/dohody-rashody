/**
 * Todo notes (separate from finance transactions).
 */

function noteFromRow_(r) {
  return {
    id: String(r[0]),
    createdAt: toIso_(r[1]),
    dueDate: r[2] ? formatYmd_(r[2]) : '',
    dueTime: r[3] ? formatHm_(r[3]) : '',
    text: r[4] ? String(r[4]) : '',
    done: r[5] === true || r[5] === 'TRUE' || r[5] === 1 || r[5] === '1',
    source: r[6] ? String(r[6]) : '',
    rawText: r[7] ? String(r[7]) : ''
  };
}

function formatYmd_(value) {
  if (!value) return '';
  var d = value instanceof Date ? value : new Date(value);
  if (isNaN(d.getTime())) return String(value).slice(0, 10);
  return Utilities.formatDate(d, Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function formatHm_(value) {
  if (!value) return '';
  if (typeof value === 'string' && /^\d{1,2}:\d{2}$/.test(value.trim())) {
    var p = value.trim().split(':');
    return ('0' + p[0]).slice(-2) + ':' + ('0' + p[1]).slice(-2);
  }
  var d = value instanceof Date ? value : new Date(value);
  if (isNaN(d.getTime())) return '';
  return Utilities.formatDate(d, Session.getScriptTimeZone(), 'HH:mm');
}

function parseDueDateInput_(ymd) {
  ymd = String(ymd || '').trim();
  if (!ymd) return '';
  parseYmdBound_(ymd, false);
  return ymd;
}

function parseDueTimeInput_(hm) {
  hm = String(hm || '').trim();
  if (!hm) return '';
  if (!/^\d{1,2}:\d{2}$/.test(hm)) {
    throw new Error('Некорректное время: ' + hm);
  }
  var p = hm.split(':');
  var h = Number(p[0]);
  var m = Number(p[1]);
  if (h < 0 || h > 23 || m < 0 || m > 59) {
    throw new Error('Некорректное время: ' + hm);
  }
  return ('0' + h).slice(-2) + ':' + ('0' + m).slice(-2);
}

function listAllNotes_() {
  var sheet = getNotesSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return [];
  var values = sheet.getRange(2, 1, last, NOTE_HEADERS.length).getValues();
  return values
    .filter(function (r) {
      return r[0];
    })
    .map(noteFromRow_);
}

/**
 * @param {{includeDone?:boolean}} opts
 * @return {Array}
 */
function listNotes(opts) {
  opts = opts || {};
  var all = listAllNotes_();
  if (!opts.includeDone) {
    all = all.filter(function (n) {
      return !n.done;
    });
  }
  all.sort(compareNotes_);
  return all;
}

function compareNotes_(a, b) {
  var aHas = !!a.dueDate;
  var bHas = !!b.dueDate;
  if (aHas && !bHas) return -1;
  if (!aHas && bHas) return 1;
  if (aHas && bHas) {
    if (a.dueDate !== b.dueDate) return a.dueDate < b.dueDate ? -1 : 1;
    var at = a.dueTime || '99:99';
    var bt = b.dueTime || '99:99';
    if (at !== bt) return at < bt ? -1 : 1;
  }
  var ac = a.createdAt || '';
  var bc = b.createdAt || '';
  if (ac !== bc) return ac > bc ? -1 : 1;
  return String(a.id).localeCompare(String(b.id));
}

function findNoteRow_(id) {
  var sheet = getNotesSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return null;
  var values = sheet.getRange(2, 1, last, NOTE_HEADERS.length).getValues();
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]) === String(id)) {
      return { rowIndex: i + 2, data: noteFromRow_(values[i]) };
    }
  }
  return null;
}

function getNote(id) {
  var found = findNoteRow_(id);
  if (!found) throw new Error('Заметка не найдена');
  return found.data;
}

/**
 * @param {Object} payload
 */
function createNote(payload) {
  payload = payload || {};
  var text = String(payload.text || '').trim();
  if (!text) throw new Error('Укажите текст заметки');

  var dueDate = parseDueDateInput_(payload.dueDate);
  var dueTime = parseDueTimeInput_(payload.dueTime);
  var done = !!payload.done;
  var source = payload.source || 'manual';
  var rawText = payload.rawText != null ? String(payload.rawText) : '';

  var now = new Date();
  var id = newId_();
  var dueDateCell = dueDate ? parseYmdBound_(dueDate, false) : '';
  var row = [id, now, dueDateCell, dueTime, text, done, source, rawText];
  getNotesSheet_().appendRow(row);
  return noteFromRow_(row);
}

function updateNote(id, payload) {
  payload = payload || {};
  var found = findNoteRow_(id);
  if (!found) throw new Error('Заметка не найдена');

  var text =
    payload.text != null ? String(payload.text).trim() : found.data.text;
  if (!text) throw new Error('Укажите текст заметки');

  var dueDate =
    payload.dueDate != null
      ? parseDueDateInput_(payload.dueDate)
      : found.data.dueDate;
  var dueTime =
    payload.dueTime != null
      ? parseDueTimeInput_(payload.dueTime)
      : found.data.dueTime;
  var done = payload.done != null ? !!payload.done : found.data.done;

  var dueDateCell = dueDate ? parseYmdBound_(dueDate, false) : '';
  var row = found.rowIndex;
  getNotesSheet_().getRange(row, 3).setValue(dueDateCell);
  getNotesSheet_().getRange(row, 4).setValue(dueTime);
  getNotesSheet_().getRange(row, 5).setValue(text);
  getNotesSheet_().getRange(row, 6).setValue(done);

  return getNote(id);
}

function deleteNote(id) {
  var found = findNoteRow_(id);
  if (!found) throw new Error('Заметка не найдена');
  getNotesSheet_().deleteRow(found.rowIndex);
  return { ok: true, id: id };
}
