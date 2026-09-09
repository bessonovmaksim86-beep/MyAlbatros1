ALTER TABLE classifier_inclusion
    ADD COLUMN quantity DECIMAL(15, 3) NULL,
    ADD COLUMN unit VARCHAR(20) NULL;

UPDATE classifier_inclusion
SET quantity = 1.000
WHERE quantity IS NULL;

UPDATE classifier_inclusion
SET unit = 'PCS'
WHERE unit IS NULL;

ALTER TABLE classifier_inclusion
    MODIFY COLUMN quantity DECIMAL(15, 3) NOT NULL,
    MODIFY COLUMN unit VARCHAR(20) NOT NULL;