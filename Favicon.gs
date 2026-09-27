/**
 * Favicon for GAS Web App browser tab.
 * HtmlService runs in an iframe — &lt;link rel=icon&gt; inside HTML does not
 * change the tab icon. HtmlOutput.setFaviconUrl(publicHttpsUrl) does.
 * We upload the PNG to Drive once (Anyone with link) and cache the URL.
 */

var PROP_FAVICON_URL = 'FAVICON_URL';

/**
 * @return {string} public https URL suitable for setFaviconUrl
 */
function getFaviconUrl_() {
  var props = PropertiesService.getScriptProperties();
  var cached = props.getProperty(PROP_FAVICON_URL);
  if (cached) return cached;

  try {
    return publishFaviconToDrive_();
  } catch (err) {
    Logger.log('favicon publish failed: ' + (err.message || err));
    // Last resort: GitHub (works only if the repo is public).
    return (
      'https://raw.githubusercontent.com/KT9995675/dohody-rashody/main/assets/favicon-32.png?v=2'
    );
  }
}

/** Force re-upload (menu). */
function publishFavicon() {
  var ui = SpreadsheetApp.getUi();
  try {
    var url = publishFaviconToDrive_();
    ui.alert(
      'Фавикон',
      'Опубликован.\nURL:\n' +
        url +
        '\n\nОткройте веб-приложение в новой вкладке (кэш иконки упорный).',
      ui.ButtonSet.OK
    );
  } catch (err) {
    ui.alert('Фавикон', err.message || String(err), ui.ButtonSet.OK);
  }
}

function publishFaviconToDrive_() {
  if (typeof FAVICON_PNG_B64_ === 'undefined' || !FAVICON_PNG_B64_) {
    throw new Error('Нет FAVICON_PNG_B64_ (FaviconData.gs)');
  }
  var blob = Utilities.newBlob(
    Utilities.base64Decode(FAVICON_PNG_B64_),
    MimeType.PNG,
    'dohody-rashody-favicon-32.png'
  );
  var file = DriveApp.createFile(blob);
  file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  var id = file.getId();
  // Thumbnail URL is reliably fetchable as an image for favicons.
  var url = 'https://drive.google.com/thumbnail?id=' + id + '&sz=w64';
  PropertiesService.getScriptProperties().setProperty(PROP_FAVICON_URL, url);
  return url;
}
