-- The registration tables plus managers/recruiters (for account-type checks), copied verbatim from hiremebot_db_V1.sql
-- (V1 revision 3), plus its users AUTO_INCREMENT start. This matches the live local hiremebot_db_v1 as inspected on
-- 2026-11-07. The optional UNIQUE (first_name, last_name) index the owner keeps is added by ScratchSchema when a test
-- asks for it. The integration tests load this into a throwaway schema (hmb_reg_it_<random>) that they create and drop
-- themselves; it is never run against hiremebot_db_v1. Statements are separated by semicolons at line ends.

SET default_storage_engine = InnoDB;

CREATE TABLE users (
    user_id BIGINT PRIMARY KEY AUTO_INCREMENT,

    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,

    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(16) NOT NULL,

    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,

    account_status ENUM('PENDING', 'ACTIVE', 'SUSPENDED') NOT NULL DEFAULT 'PENDING',

    terms_accepted_at TIMESTAMP NULL DEFAULT NULL,
    terms_version VARCHAR(30) NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE user_identities (
    identity_id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,

    provider ENUM('GOOGLE', 'COGNITO') NOT NULL,

    cognito_sub VARCHAR(128) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uq_cognito_sub UNIQUE (cognito_sub),

    CONSTRAINT uq_user_provider UNIQUE (user_id, provider),

    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE managers (
    manager_id BIGINT PRIMARY KEY,
    FOREIGN KEY (manager_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE recruiters (
    recruiter_id  BIGINT PRIMARY KEY,
    manager_id BIGINT NULL,

    FOREIGN KEY (manager_id) REFERENCES managers(manager_id) ON DELETE RESTRICT,
    FOREIGN KEY (recruiter_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE students (
    student_id BIGINT PRIMARY KEY,
    FOREIGN KEY (student_id) REFERENCES users(user_id) ON DELETE CASCADE
);

ALTER TABLE users AUTO_INCREMENT = 1000000000;
