-- The same body as Identity's V1. A function lives in one database, and
-- database-per-service means Order's database has none of Identity's, so this
-- is duplication the architecture requires rather than copy-paste.
--
-- Why GREATEST and not plain clock_timestamp(): it makes updated_at STRICTLY
-- increasing per row. clock_timestamp() alone can return the same value for
-- two updates inside one microsecond, and Notification orders events by this
-- column with a strict `<`, so an equal timestamp would silently drop the
-- second update. Order has no such consumer today, but the trigger is the one
-- place that guarantee can live, and a second copy of the function that
-- behaves differently would be worse than either.
CREATE FUNCTION bump_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := GREATEST(clock_timestamp(), OLD.updated_at + interval '1 microsecond');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
