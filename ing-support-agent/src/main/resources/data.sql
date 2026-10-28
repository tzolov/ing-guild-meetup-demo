DELETE FROM mortgage_payments;
DELETE FROM customers;

INSERT INTO customers VALUES (1001, 'Anna', 2180.55);
INSERT INTO customers VALUES (1002, 'Bram', 540.10);
INSERT INTO customers VALUES (1003, 'Fatima', 9320.00);

-- Anna: the October direct debit bounced, a retry is planned, the following months are scheduled
INSERT INTO mortgage_payments VALUES (1001, '2026-09', '2026-09-01', 1450.00, 'PAID', NULL);
INSERT INTO mortgage_payments VALUES (1001, '2026-10', '2026-10-01', 1450.00, 'FAILED', 'Direct debit rejected: balance was 312.40 on the due date');
INSERT INTO mortgage_payments VALUES (1001, '2026-10', '2026-10-28', 1450.00, 'RETRY_PLANNED', 'Automatic retry of the October payment');
INSERT INTO mortgage_payments VALUES (1001, '2026-11', '2026-11-01', 1450.00, 'SCHEDULED', NULL);
INSERT INTO mortgage_payments VALUES (1001, '2026-12', '2026-12-01', 1450.00, 'SCHEDULED', NULL);

INSERT INTO mortgage_payments VALUES (1002, '2026-10', '2026-10-01', 980.00, 'PAID', NULL);
INSERT INTO mortgage_payments VALUES (1002, '2026-11', '2026-11-01', 980.00, 'SCHEDULED', NULL);

INSERT INTO mortgage_payments VALUES (1003, '2026-10', '2026-10-01', 2100.00, 'PAID', NULL);
INSERT INTO mortgage_payments VALUES (1003, '2026-11', '2026-11-01', 2100.00, 'SCHEDULED', NULL);
