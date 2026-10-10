-- Outside the supported set: sqlcj cannot read SET DATA TYPE.
ALTER TABLE tasks ALTER COLUMN estimate_hours SET DATA TYPE numeric(8, 2);
