// node summarize.mjs <latency.jsonl>; audio timings use samples, never transcript arrival.
import { readFileSync } from 'node:fs';
const runs = [];
for (const line of readFileSync(process.argv[2], 'utf8').trim().split('\n')) {
  const row = JSON.parse(line);
  if (row.event === 'mic_on') runs.push([]);
  runs.at(-1)?.push(row);
}
for (const [i, rows] of runs.entries()) {
  const ready = rows.find((r) => r.event === 'ready');
  const end = rows.find((r) => r.event === 'speech_end')?.end;
  const first = rows.find((r) => r.event === 'audio');
  const started = rows.find((r) => r.event === 'task.running');
  const done = rows.find((r) => r.event === 'task.done');
  const spoken = done && rows.find((r) => r.event === 'audio' && r.at >= done.at);
  console.log(JSON.stringify({ run: i + 1, ready_ms: ready?.ms, reply_ms: end && first ? first.at - end : null,
    task_start_ms: end && started ? started.at - end : null, done_speech_ms: spoken ? spoken.at - done.at : null,
    tasks: rows.filter((r) => r.event === 'task.created').length,
    transcript: rows.filter((r) => r.event === 'turn.done').map((r) => `${r.role}: ${r.text}`) }));
}
