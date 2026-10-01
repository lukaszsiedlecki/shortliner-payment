-- Payments now belong to a user: the Keycloak subject (jwt.sub, a UUID
-- string). Nullable only because rows created before auth existed have no
-- owner; the code requires it for every new payment.
ALTER TABLE payments ADD COLUMN user_id VARCHAR(36);

-- Idempotency keys are scoped per user, so two users can't collide on (or
-- probe for) each other's keys. Rows with a NULL user_id never conflict with
-- each other under this constraint, which is fine: they're historical and
-- were already globally unique under the old one.
ALTER TABLE payments DROP CONSTRAINT uk_payments_idempotency_key;
ALTER TABLE payments ADD CONSTRAINT uk_payments_user_id_idempotency_key UNIQUE (user_id, idempotency_key);

-- Serves "my payments, newest first". The unique constraint above also leads
-- with user_id, but this one matches the list query's ORDER BY.
CREATE INDEX idx_payments_user_id_created_at ON payments (user_id, created_at);
