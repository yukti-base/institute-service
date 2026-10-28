-- Minimal stand-ins for tables institute-service reads but does not own.
--
-- InstituteDependencyGuard counts rows in user_role_assignments, posting_targets
-- and placement_offers to decide whether a delete is safe. Those belong to other
-- bounded contexts and are deliberately absent from this service's @EntityScan,
-- so ddl-auto=create-drop never creates them and every guard query would fail
-- with "relation does not exist".
--
-- In the shared database they exist and are owned by admin-service. Here we
-- create only the columns the guard actually reads. Deliberately no foreign keys
-- and no extra columns: this is a counting surface, not a schema replica, and
-- pretending otherwise would rot the moment the real tables change.
CREATE TABLE IF NOT EXISTS user_role_assignments (
    id        bigserial PRIMARY KEY,
    college_id bigint,
    dept_id    bigint,
    degree_id  bigint,
    is_active  boolean NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS posting_targets (
    id            bigserial PRIMARY KEY,
    college_id    bigint,
    department_id bigint,
    degree_id     bigint,
    is_deleted    boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS placement_offers (
    id         bigserial PRIMARY KEY,
    college_id bigint,
    is_deleted boolean NOT NULL DEFAULT false
);

-- Truncated on every run. ddl-auto=create-drop disposes of the institute tables
-- for us, but these three are outside Hibernate's control and would otherwise
-- accumulate a run's worth of guard fixtures every time the suite executes.
TRUNCATE user_role_assignments, posting_targets, placement_offers;
