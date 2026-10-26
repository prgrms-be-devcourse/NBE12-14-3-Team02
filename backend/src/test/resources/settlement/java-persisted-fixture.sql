-- Generated with Java entities at commit 1134c4d.
-- Generator: src/test/fixtures/settlement/JavaSettlementFixtureGeneratorTest.java
-- Only timestamps are normalized. Do not regenerate with Kotlin entities.
INSERT INTO expenses (`id`, `amount`, `created_at`, `meeting_id`, `memo`, `payer_member_id`, `receipt_key`, `remainder_member_id`, `rounding_unit`, `split_mode`, `title`) VALUES (900000, 10, '2026-10-01T12:34:56.123456', 890001, NULL, 41, NULL, NULL, NULL, NULL, '기존 균등 지출');
INSERT INTO expenses (`id`, `amount`, `created_at`, `meeting_id`, `memo`, `payer_member_id`, `receipt_key`, `remainder_member_id`, `rounding_unit`, `split_mode`, `title`) VALUES (900001, 3000000001, '2026-10-01T12:34:56.123456', 890001, 'Java에서 저장', 17, 'java/receipt', NULL, NULL, 'EXACT', '큰 지정 지출');
INSERT INTO expenses (`id`, `amount`, `created_at`, `meeting_id`, `memo`, `payer_member_id`, `receipt_key`, `remainder_member_id`, `rounding_unit`, `split_mode`, `title`) VALUES (900002, 10000, '2026-10-01T12:34:56.123456', 890001, NULL, 41, NULL, 41, 100, 'EQUAL', '단위 분담');
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900000, 5);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900000, 17);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900000, 41);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900001, 5);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900001, 17);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900001, 41);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900002, 5);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900002, 17);
INSERT INTO expense_participants (`expense_id`, `member_id`) VALUES (900002, 41);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900001, 0, 5);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900001, 1, 17);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900001, 3000000000, 41);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900002, 3300, 5);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900002, 3300, 17);
INSERT INTO expense_shares (`expense_id`, `share_amount`, `member_id`) VALUES (900002, 3400, 41);
INSERT INTO expense_registrations (`id`, `expense_id`, `fingerprint`, `meeting_id`, `member_id`, `request_key`) VALUES (900000, 900001, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 890001, 17, '12345678-1234-1234-1234-123456789abc');
INSERT INTO settlement_accounts (`id`, `account_holder`, `account_number`, `bank_name`, `meeting_id`, `member_id`) VALUES (900000, ' 예금주', '001-234', '은행', 890001, 17);
INSERT INTO settlements (`id`, `closed_at`, `closed_by_member_id`, `meeting_id`, `status`, `version`) VALUES (900000, '2026-10-02T13:45:57.654321', 41, 890001, 'CLOSED', 0);
INSERT INTO settlements (`id`, `closed_at`, `closed_by_member_id`, `meeting_id`, `status`, `version`) VALUES (900001, NULL, NULL, 890002, 'OPEN', 0);
INSERT INTO settlement_balances (`settlement_id`, `member_id`, `paid_amount`, `share_amount`, `balance_order`) VALUES (900000, 5, 0, 3304, 0);
INSERT INTO settlement_balances (`settlement_id`, `member_id`, `paid_amount`, `share_amount`, `balance_order`) VALUES (900000, 17, 3000000001, 3304, 1);
INSERT INTO settlement_balances (`settlement_id`, `member_id`, `paid_amount`, `share_amount`, `balance_order`) VALUES (900000, 41, 10010, 3000003403, 2);
INSERT INTO settlement_transfers (`settlement_id`, `amount`, `recipient_id`, `sender_id`, `transfer_order`) VALUES (900000, 3304, 17, 5, 0);
INSERT INTO settlement_transfers (`settlement_id`, `amount`, `recipient_id`, `sender_id`, `transfer_order`) VALUES (900000, 2999993393, 17, 41, 1);
INSERT INTO settlement_account_snapshots (`settlement_id`, `account_holder`, `account_number`, `bank_name`, `member_id`, `account_order`) VALUES (900000, ' 예금주', '001-234', '은행', 17, 0);
