# Samsung Health export → phone memory

From the repo root on the Mac, with the test phone connected over USB:

```sh
node agentd/memory/cli.js consent grant samsung_health
node agentd/memory/cli.js import-samsung-health "$HOME/Downloads/samsunghealth_YOUR_EXPORT_FOLDER"
node agentd/memory/cli.js consent revoke samsung_health
```

The first command explicitly permits redacted metrics in cloud-model answers (the existing memory `cloud` scope). Use `--scope local` on the grant for on-device models only; cloud tools then refuse the source. Health is never default-granted. Revoke deletes all six health tables' contents in the same transaction as its ledger entry, with SQLite secure-delete and WAL truncation. It does not delete the owner's original Mac export or provider conversation history.

The Mac checks the phone grant before opening the folder, normalizes the CSVs, and sends only the compact payload through a length-framed stdin pipe over `adb shell -T` to the existing Termux memory DB. Every mutation acquires `scripts/withphone`; do not wrap these Mac CLI commands in a second lock. It never downloads, replaces or copies the live database. Imports need no restart once the modules are deployed. `--db /tmp/test.db` selects an explicit local test database instead of the phone.

Ask or speak: “How did I sleep last week?”, “How did I sleep this week?”, “What's my resting heart rate trend?” The health plugin supplies `health_summary {days}` and `health_query {metric,from,to}` to the same task engine used by voice. Query dates are inclusive; `summary` compares the last N local dates through today against the preceding N. Missing dates are not zeroes. Last night means sessions ending today; `latest` is separately dated so stale exports are not presented as last night.

## Format and decisions

- CSV first row is metadata; second row is the header. UTF-8 BOM, CRLF, quoted commas/newlines, doubled quotes, trailing empty cells and namespaced headers are supported. Files match the exact datatype plus numeric export timestamp; exercise extensions and sleep-combined files cannot accidentally count twice.
- `start_time`/`end_time` are UTC strings or epoch milliseconds. Numeric millisecond offsets and `UTC-06:00`/`+05:30` are supported. Sleep belongs to its local wake date; HR/exercise to their local start date. `day_time` is a date encoded as midnight UTC, so its offset must **not** be applied.
- Tables: `health_sleep` (session bounds, duration, aggregated stage minutes, asleep time when stage coverage is complete, efficiency/score), `health_heart_rate` (daily exported-measurement mean, maximum, explicitly supplied resting HR), `health_steps`, `health_hrv` (daily SDNN/RMSSD means), `health_stress` (daily score mean/max), `health_exercise` (type code, active duration, distance/calories). Sleep average includes naps and split sessions on each wake date; recorded duration includes awake time. We never infer asleep time from efficiency.
- `min` HR is not resting HR. The legacy documented HR columns have no resting field. An optional `resting_heart_rate` column is accepted when supplied by an export version, otherwise the result says unknown. No resting trend is invented from minimum or sleeping HR. An explicit exercise `sensing_status.heart_rate.rhr` is also accepted. If neither exists, the correct answer is that no resting-HR trend is available. Sleeping HR and calibration `base_hr` are not resting HR.
- Prefer the all-device step row (`source_type=-2` or `source_info`), otherwise report the largest device total as such, never a sum. Repeated exports replace each session/day. HR sample IDs are deduplicated within an import; raw measurements/bins are discarded after computing daily totals. Use a full export for each import: a partial-day HR file replaces that day's previous statistics.
- The Mac reads numeric HR/HRV/stress bins and explicit exercise resting HR from `jsons.zip` through a Python standard-library helper, without extracting the archive. It computes daily aggregates and discards the bins. `com.samsung.shealth.stress.histogram` is calibration state, not a dated stress measurement, so it is excluded; `com.samsung.shealth.stress` scores are supported. SpO2, GPS, comments, device/account identifiers and unknown CSV types are skipped. Import counts include ignored files and invalid rows. Dates/metrics are validated; malformed CSVs abort without changing the database. This is export ingestion, not live Watch pairing.
- The returned data is marked `private` and the tool-result event carries the Rule of Two private lamp. Cloud output is an allowlist of numeric metrics and dates, with source UUIDs and arbitrary export text removed. Health never enters the general memory graph, FTS, fact extractor or automatic context. Health values themselves remain visible to cloud models only with a cloud grant, exactly as other memory sources permit redacted content.

## Sources

[Samsung constants](https://developer.samsung.com/health/android/data/api-reference/constant-values.html) document the column names, stage codes 40001–40004, HR min/max, exercise fields and units. [Sleep stages](https://developer.samsung.com/health/android/data/api-reference/com/samsung/android/sdk/healthdata/HealthConstants.SleepStage.html) document session linkage and UTC/time offsets. [Daily step trends](https://developer.samsung.com/health/android/data/api-reference/com/samsung/android/sdk/healthdata/HealthConstants.StepDailyTrend.html) document all-device deduplication and `day_time` semantics. Samsung's SDK documentation is not a versioned personal-export specification: export-specific metadata, prefixed columns and filenames were cross-checked against the [export parser author's reader](https://github.com/v-2841/samsung-health-export/blob/main/shealth_export/csvio.py) and [field registry](https://github.com/v-2841/samsung-health-export/blob/main/shealth_export/registry.py). Recheck coverage counts against the founder's actual export when it arrives.

## Synthetic verification

```sh
node agentd/memory/samsung-health-test.js
node agentd/memory/test.js
node agentd/memory/samsung-synthetic.js /tmp/agentos-health-SYNTHETIC 2026-10-28
```

The generator contains arithmetic values and conspicuous FAKE IDs, never real data. It produces 21 nights with stages, 63 HR samples, paired phone/all-device step totals, seven exercise sessions, one stress score and an ignored SpO2 file. Separate synthetic archive tests cover the real tracker-HR/exercise filenames, HRV sidecars and calibration exclusion. Tests separately exercise the optional resting-HR column and explicit exercise resting-HR sidecar. Raw CSVs, export folders, JSON archives and database files are ignored by git; only the generator is committed. Keep real exports outside the repo.

The phone smoke test used the synthetic folder, then revoked the source and checked deletion before the owner's real import. Independent CSV/JSON calculations and a private phone-table comparison verify all supported metrics; real records and answer text are never committed or printed. This export may lack explicit resting HR or stress scores even when it includes a calibration histogram. The tools report missing data rather than inventing a trend.
