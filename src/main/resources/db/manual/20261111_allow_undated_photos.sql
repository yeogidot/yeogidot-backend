-- Apply before deploying a backend that accepts photos without a capture date.
ALTER TABLE photo MODIFY COLUMN taken_at DATETIME(6) NULL;
