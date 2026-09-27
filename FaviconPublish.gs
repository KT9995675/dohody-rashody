/**
 * Favicon for GAS Web App browser tab.
 * HtmlService runs in an iframe — &lt;link rel=icon&gt; inside HTML does not
 * change the tab icon. HtmlOutput.setFaviconUrl(publicHttpsUrl) does.
 *
 * Hosted as a public GitHub gist (no Google Drive access required).
 * Gist: https://gist.github.com/KT9995675/4c659a1aefd6e8a02f74a70ef860f88b
 */

var FAVICON_URL_ =
  'https://gist.githubusercontent.com/KT9995675/4c659a1aefd6e8a02f74a70ef860f88b/raw/favicon.svg?v=2';

function getFaviconUrl_() {
  return FAVICON_URL_;
}
