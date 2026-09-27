/**
 * Favicon for GAS Web App tab via HtmlOutput.setFaviconUrl.
 * Pattern as in math-trainer: Drive uc?export=view&id=…#.png
 * (#.png — Apps Script checks “extension” at end of URL).
 *
 * Fallback: GitHub raw PNG if Drive is blocked for the browser.
 */

var PROP_FAVICON_URL = 'FAVICON_URL';

/** User’s new favicon.png on Drive (shared). */
var FAVICON_DRIVE_FILE_ID_ = '1YxtY0aqQJO2EBu_bMRiMAgEJKypXF3u4';

/** Fallback if Drive 403 — assets/favicon-v2-32.png in the repo. */
var FALLBACK_FAVICON_URL =
  'https://raw.githubusercontent.com/KT9995675/dohody-rashody/main/assets/favicon-v2-32.png';

function driveFaviconUrl_(fileId) {
  return 'https://drive.google.com/uc?export=view&id=' + fileId + '#.png';
}

function driveIdFromUrlOrId_(s) {
  s = String(s || '').trim();
  if (!s) return '';
  var m = s.match(/\/file\/d\/([^/]+)/);
  if (m) return m[1];
  m = s.match(/[?&]id=([^&#]+)/);
  if (m) return m[1];
  if (/^[a-zA-Z0-9_-]{20,}$/.test(s)) return s;
  return '';
}

function getFaviconPublicUrl_() {
  var props = PropertiesService.getScriptProperties();
  var custom = String(props.getProperty(PROP_FAVICON_URL) || '').trim();
  if (custom === 'github' || custom === 'fallback') return FALLBACK_FAVICON_URL;
  if (custom) {
    var id = driveIdFromUrlOrId_(custom);
    if (id) return driveFaviconUrl_(id);
    if (/^https:\/\//i.test(custom)) return custom;
  }
  if (FAVICON_DRIVE_FILE_ID_) return driveFaviconUrl_(FAVICON_DRIVE_FILE_ID_);
  return FALLBACK_FAVICON_URL;
}

function getFaviconUrl_() {
  return getFaviconPublicUrl_();
}

/** Menu: Drive link/id, https://…png, or слово «github» для raw из репо. */
function setupFaviconUrl() {
  var ui = SpreadsheetApp.getUi();
  var props = PropertiesService.getScriptProperties();
  var cur = (props.getProperty(PROP_FAVICON_URL) || '').trim();
  var resp = ui.prompt(
    'Фавикон',
    'Ссылка Drive / ID / https://…png / «github».\n' +
      'Сейчас: ' +
      (cur || FAVICON_DRIVE_FILE_ID_ || '—') +
      '\n\nНовое (пусто = Drive по умолчанию):',
    ui.ButtonSet.OK_CANCEL
  );
  if (resp.getSelectedButton() !== ui.Button.OK) return;
  var raw = String(resp.getResponseText() || '').trim();
  if (!raw) {
    props.deleteProperty(PROP_FAVICON_URL);
    ui.alert('Фавикон', 'Drive:\n' + getFaviconPublicUrl_(), ui.ButtonSet.OK);
    return;
  }
  props.setProperty(PROP_FAVICON_URL, raw);
  ui.alert('Фавикон', 'URL: ' + getFaviconPublicUrl_() + '\n\nНовая вкладка веб-приложения.', ui.ButtonSet.OK);
}
