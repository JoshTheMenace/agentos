// ContactsContract (content://com.android.contacts/data/phones + /emails). Full read each time (103 rows is nothing);
// person() merges by phone/email so re-reads add nothing.
import { INGESTORS } from '../ingest.js';
export default { source: 'contacts', every: 60 * 60e3, sync: (db) => ({ ...INGESTORS.contacts(db), added: 0 }) };
