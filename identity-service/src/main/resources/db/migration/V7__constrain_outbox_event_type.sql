-- event_type was any string up to 50 characters, and the whole interpretation
-- of aggregate_id rests on it: 'BrandUpserted' means aggregate_id is a brand
-- id, 'UserUpserted' means it is a user id. Brand 7 and user 7 are different
-- rows sharing a number, so a typo ('BrandUpdated', 'brandUpserted') leaves
-- aggregate_id pointing at nothing identifiable AND produces an event no
-- consumer recognises, both silently. Pinning the vocabulary makes
-- "event_type determines the aggregate type" guaranteed rather than assumed.
--
-- Deliberately NOT an aggregate_type column: it would be fully derivable from
-- event_type, and a second copy of derivable state is what `saga_state` was
-- dropped for.
--
-- Adding an event type now needs a migration. That is proportionate here --
-- the system's event list was cut to four on purpose and each addition is a
-- documented decision, so this keeps that decision explicit.
ALTER TABLE outbox
    ADD CONSTRAINT ck_outbox_event_type_known
        CHECK (event_type IN ('BrandUpserted', 'UserUpserted'));
