/**
 * @return {Array<{id:string,name:string,createdAt:string}>}
 */
function listCategories() {
  var sheet = getCatSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return [];
  var values = sheet.getRange(2, 1, last, CAT_HEADERS.length).getValues();
  return values
    .filter(function (r) {
      return r[0];
    })
    .map(function (r) {
      return {
        id: String(r[0]),
        name: String(r[1]),
        createdAt: toIso_(r[2])
      };
    })
    .sort(function (a, b) {
      return a.name.localeCompare(b.name, 'ru');
    });
}

/**
 * @param {string} name
 * @return {{id:string,name:string}|null}
 */
function findCategoryByName_(name) {
  if (!name) return null;
  var needle = String(name).trim().toLowerCase();
  var list = listCategories();
  for (var i = 0; i < list.length; i++) {
    if (list[i].name.toLowerCase() === needle) return list[i];
  }
  return null;
}

/**
 * @param {string} name
 * @return {{id:string,name:string,createdAt:string}}
 */
function addCategory(name) {
  name = String(name || '').trim();
  if (!name) throw new Error('Укажите название категории');
  if (findCategoryByName_(name)) {
    throw new Error('Категория уже существует: ' + name);
  }
  var sheet = getCatSheet_();
  var row = [newId_(), name, new Date()];
  sheet.appendRow(row);
  return { id: row[0], name: row[1], createdAt: toIso_(row[2]) };
}

/**
 * Ensures category exists; creates if missing (after user confirmed).
 * @param {string} name
 * @return {string} canonical name
 */
function ensureCategoryExists_(name) {
  name = String(name || '').trim();
  if (!name) return '';
  var existing = findCategoryByName_(name);
  if (existing) return existing.name;
  return addCategory(name).name;
}

/**
 * @param {string} id
 * @param {string} newName
 */
function renameCategory(id, newName) {
  newName = String(newName || '').trim();
  if (!newName) throw new Error('Укажите новое название');
  var sheet = getCatSheet_();
  var last = sheet.getLastRow();
  if (last < 2) throw new Error('Категория не найдена');

  var values = sheet.getRange(2, 1, last, CAT_HEADERS.length).getValues();
  var rowIndex = -1;
  var oldName = '';
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]) === String(id)) {
      rowIndex = i + 2;
      oldName = String(values[i][1]);
      break;
    }
  }
  if (rowIndex < 0) throw new Error('Категория не найдена');

  var clash = findCategoryByName_(newName);
  if (clash && clash.id !== id) {
    throw new Error('Категория уже существует: ' + newName);
  }

  sheet.getRange(rowIndex, 2).setValue(newName);
  replaceCategoryInTransactions_(oldName, newName);
  return { id: id, name: newName };
}

/**
 * @param {string} id
 * @param {string=} replacementName required if transactions use this category
 */
function deleteCategory(id, replacementName) {
  var sheet = getCatSheet_();
  var last = sheet.getLastRow();
  if (last < 2) throw new Error('Категория не найдена');

  var values = sheet.getRange(2, 1, last, CAT_HEADERS.length).getValues();
  var rowIndex = -1;
  var name = '';
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]) === String(id)) {
      rowIndex = i + 2;
      name = String(values[i][1]);
      break;
    }
  }
  if (rowIndex < 0) throw new Error('Категория не найдена');

  var usage = countTransactionsWithCategory_(name);
  if (usage > 0) {
    replacementName = String(replacementName || '').trim();
    if (!replacementName) {
      throw new Error(
        'Категория используется в ' + usage + ' записях. Укажите категорию для замены.'
      );
    }
    if (replacementName.toLowerCase() === name.toLowerCase()) {
      throw new Error('Категория замены должна отличаться от удаляемой');
    }
    var repl = findCategoryByName_(replacementName);
    if (!repl) {
      throw new Error('Категория замены не найдена: ' + replacementName);
    }
    replaceCategoryInTransactions_(name, repl.name);
  }

  sheet.deleteRow(rowIndex);
  return { ok: true, deleted: name, replacedWith: usage > 0 ? replacementName : null };
}

function countTransactionsWithCategory_(name) {
  var needle = String(name).toLowerCase();
  var sheet = getTxSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return 0;
  var values = sheet.getRange(2, 6, last, 6).getValues();
  var n = 0;
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]).toLowerCase() === needle) n++;
  }
  return n;
}

function replaceCategoryInTransactions_(oldName, newName) {
  var needle = String(oldName).toLowerCase();
  var sheet = getTxSheet_();
  var last = sheet.getLastRow();
  if (last < 2) return;
  var range = sheet.getRange(2, 6, last, 6);
  var values = range.getValues();
  var changed = false;
  for (var i = 0; i < values.length; i++) {
    if (String(values[i][0]).toLowerCase() === needle) {
      values[i][0] = newName;
      changed = true;
    }
  }
  if (changed) range.setValues(values);
}

/**
 * Category usage count for UI before delete.
 * @param {string} id
 */
function getCategoryUsage(id) {
  var list = listCategories();
  var cat = null;
  for (var i = 0; i < list.length; i++) {
    if (list[i].id === id) {
      cat = list[i];
      break;
    }
  }
  if (!cat) throw new Error('Категория не найдена');
  return { id: cat.id, name: cat.name, count: countTransactionsWithCategory_(cat.name) };
}
