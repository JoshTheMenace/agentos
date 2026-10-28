// Currently posted notifications (dumpsys). Live messages arrive through context.js -> memory.noteMessage as they are
// posted; this sweep catches what was posted while agentd was down. Private apps and OTPs are dropped in ingest.js.
import { INGESTORS } from '../ingest.js';
export default { source: 'notifications', every: 10 * 60e3, sync: (db) => INGESTORS.notifications(db) };
