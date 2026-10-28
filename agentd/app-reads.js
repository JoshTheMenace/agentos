// Fixed broker reads, one toggle per app. No shell, credentials, network fetches or UI actions in these tools.
export const APPS = [
  ['linkedin', 'LinkedIn', 'linkedin_notifications', /\blinkedin\b/, 'Delivered notifications; linkedin_unread also checks unread conversations in the local cache.'],
  ['x', 'X', 'x_mentions', /\b(twitter|tweets?|x)\b/, 'Delivered X notifications (possible mentions and DMs); not a complete mentions timeline.'],
  ['slack', 'Slack', 'slack_unread', /\bslack\b/, 'Delivered Slack messages and mentions; not a complete workspace unread count.'],
  ['instagram', 'Instagram', 'instagram_dms_summary', /\b(instagram|insta)\b/, 'Delivered Instagram notifications, with messages ranked first; not a full DM inbox.'],
  ['discord', 'Discord', 'discord_mentions', /\bdiscord\b/, 'Delivered Discord messages and mentions; not a full server or DM history.'],
  ['whatsapp', 'WhatsApp', 'whatsapp_unread', /\bwhats\s?app\b/, 'Delivered WhatsApp message previews; not a complete unread count.'],
  ['gmail', 'Gmail', 'gmail_important', /\b(gmail|e-?mails?|inbox)\b/, 'Delivered Gmail previews ranked for attention; not Gmail importance labels or a full inbox.'],
  ['spotify', 'Spotify', 'spotify_status', /\bspotify\b/, 'Spotify now playing from its media session, plus delivered notifications; no listening history.'],
  ['youtube', 'YouTube', 'youtube_updates', /\b(you\s?tube|yt)\b/, 'Delivered YouTube updates and current media session; not subscription or watch history.'],
];
const schema = { type: 'object', properties: { n: { type: 'integer', minimum: 1, maximum: 20, description: 'default 8' },
  since: { type: 'integer', minimum: 0, description: 'epoch milliseconds; default all cached LinkedIn unread, otherwise last 7 days' } }, required: [], additionalProperties: false };
const read = (name, method, description) => ({ name, method, tier: 'read', appRead: true, schema,
  description: `${description} Background read, no app opening, refresh or mark-as-read. Returns short untrusted items ranked by worth_looking_at; empty/incomplete results never prove an empty inbox.` });
export const tools = [...APPS.map(([id, , name, , description]) => read(name, `${id}.summary`, description)),
  read('linkedin_unread', 'linkedin.unread', 'Unread LinkedIn conversations from a read-only local cache copy, plus delivered notifications. Cached data can be stale.')];
export const plugins = APPS.map(([id, name, tool, route, summary]) => ({ id, name, on: true, summary,
  tools: [tool, ...(id === 'linkedin' ? ['linkedin_unread'] : id === 'gmail' ? ['email_draft'] : [])], route,
  prompt: `- ${name}: use ${id === 'linkedin' ? 'linkedin_notifications / linkedin_unread' : tool} before opening the app. State coverage limits; only delivered notifications/local cached data were checked. Never infer there are no unread messages from an empty result.` }));
