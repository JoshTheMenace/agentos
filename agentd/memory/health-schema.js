// Health rows deliberately stay out of the general memory graph, FTS and fact extractors.
export const HEALTH_TABLES = ['health_sleep', 'health_heart_rate', 'health_steps', 'health_exercise', 'health_hrv', 'health_stress'];
export const HEALTH_SCHEMA = `
CREATE TABLE IF NOT EXISTS health_sleep(id TEXT PRIMARY KEY, day TEXT NOT NULL, start INTEGER NOT NULL,
  end INTEGER NOT NULL, offset INTEGER NOT NULL, duration_minutes REAL NOT NULL, asleep_minutes REAL,
  stages TEXT NOT NULL, efficiency REAL, score REAL);
CREATE INDEX IF NOT EXISTS health_sleep_day ON health_sleep(day);
CREATE TABLE IF NOT EXISTS health_heart_rate(day TEXT PRIMARY KEY, resting REAL, avg REAL, max REAL, samples INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS health_steps(day TEXT PRIMARY KEY, steps INTEGER NOT NULL, coverage TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS health_exercise(id TEXT PRIMARY KEY, day TEXT NOT NULL, start INTEGER NOT NULL,
  end INTEGER NOT NULL, offset INTEGER NOT NULL, duration_minutes REAL NOT NULL, type INTEGER,
  distance_m REAL, calories_kcal REAL);
CREATE INDEX IF NOT EXISTS health_exercise_day ON health_exercise(day);
CREATE TABLE IF NOT EXISTS health_hrv(day TEXT PRIMARY KEY, sdnn_avg REAL, rmssd_avg REAL, samples INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS health_stress(day TEXT PRIMARY KEY, avg REAL, max REAL, samples INTEGER NOT NULL);
`;
