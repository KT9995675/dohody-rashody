var SHEET_TRANSACTIONS = 'Transactions';
var SHEET_CATEGORIES = 'Categories';

var TX_HEADERS = [
  'id',
  'createdAt',
  'date',
  'type',
  'amount',
  'category',
  'comment',
  'source',
  'rawText'
];

var CAT_HEADERS = ['id', 'name', 'createdAt'];

var PROP_OWNER_EMAIL = 'OWNER_EMAIL';
var PROP_GEMINI_API_KEY = 'GEMINI_API_KEY';
/** e.g. gemini-3.0-flash, gemini-3.1-flash-lite */
var PROP_GEMINI_MODEL = 'GEMINI_MODEL';
/** Shared secret for Android → doPost (?token=) */
var PROP_MOBILE_TOKEN = 'MOBILE_API_TOKEN';

var DEFAULT_CATEGORIES = [
  'Еда',
  'Транспорт',
  'Жильё',
  'Здоровье',
  'Развлечения',
  'Прочее'
];
