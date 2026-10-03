-- Phase 4: who asked for each money movement, like the "maker" a core banking system keeps on every transaction:
-- user:<id> for a customer or an operator, apikey:<id> for a machine client such as the bank integration.

ALTER TABLE transfers ADD COLUMN initiated_by VARCHAR(64);

-- Movements recorded before callers were authenticated.
UPDATE transfers SET initiated_by = 'anonymous' WHERE initiated_by IS NULL;

ALTER TABLE transfers ALTER COLUMN initiated_by SET NOT NULL;
