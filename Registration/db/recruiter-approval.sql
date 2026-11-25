-- =====================================================================================================================
-- HireMeBot: manual recruiter approval (operator only, MySQL Workbench). The application never runs this script and
-- has no approval API: this UPDATE is the only way a recruiter becomes ACTIVE.
--
-- Use it on database hiremebot_db_v1 with a MySQL account that may UPDATE users. Run the statements ONE BY ONE:
--   1. Run the first SELECT and choose exactly one recruiter (note its user_id).
--   2. Edit the number in the SET statement below (the default 0 matches nothing, so running it unedited is harmless).
--   3. Run SET, then UPDATE, then SELECT ROW_COUNT() straight after the UPDATE (any statement in between resets it).
--   4. ROW_COUNT() must be 1. Workbench's Action Output must say "1 row(s) affected". If it is 0, nothing was
--      approved: the id is not a PENDING, non-deleted recruiter (a student, manager, ACTIVE, SUSPENDED or deleted user
--      is never changed by this statement). Never "fix" a 0 by editing the WHERE clause.
--   5. Run the last SELECT to confirm: account_status = ACTIVE, phone_verified = 0 (approval never verifies a phone).
--
-- Workbench "Safe Updates" mode accepts the UPDATE because user_id is the primary key. Do not turn it off.
-- After approval the recruiter's next GET /api/v1/recruiters/auth/me returns RECRUITER_DASHBOARD; no Cognito change
-- is needed. To undo an approval (or suspend a recruiter) set account_status back to 'PENDING' / 'SUSPENDED' for that
-- one user_id; there is no audit table in V1, so keep your own record of who approved whom and when.
-- =====================================================================================================================

-- 1. Recruiters waiting for approval.
SELECT
    u.user_id,
    u.first_name,
    u.last_name,
    u.email,
    u.phone,
    u.account_status,
    u.created_at
FROM users u
JOIN recruiters r
    ON r.recruiter_id = u.user_id
WHERE u.account_status = 'PENDING'
  AND u.is_deleted = FALSE
ORDER BY u.created_at DESC;

-- 2. The recruiter to approve: replace 0 with the user_id from step 1.
SET @recruiter_id = 0;

-- 3. Approve exactly that recruiter.
UPDATE users
SET account_status = 'ACTIVE'
WHERE user_id = @recruiter_id
  AND account_status = 'PENDING'
  AND is_deleted = FALSE
  AND EXISTS (
      SELECT 1
      FROM recruiters
      WHERE recruiter_id = users.user_id
  );

-- 4. Rows changed by the UPDATE above: must be exactly 1.
SELECT ROW_COUNT() AS rows_changed;

-- 5. Verify the result.
SELECT
    u.user_id,
    u.first_name,
    u.last_name,
    u.account_status,
    u.email_verified,
    u.phone_verified,
    u.is_deleted,
    r.manager_id
FROM users u
JOIN recruiters r
    ON r.recruiter_id = u.user_id
WHERE u.user_id = @recruiter_id;
