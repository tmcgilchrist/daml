--  Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
--  SPDX-License-Identifier: Apache-2.0



CREATE TABLE participant_users (
    internal_id         SERIAL          PRIMARY KEY,
    user_id             VARCHAR(256)    NOT NULL UNIQUE,
    primary_party       VARCHAR(512)    NOT NULL,
    created_at          BIGINT          NOT NULL DEFAULT (1000 * 1000 * EXTRACT(epoch FROM CURRENT_TIMESTAMP))::bigint
);
COMMENT ON COLUMN participant_users.created_at IS
    'Up to and including Postgres v13 EXTRACT function returns double type and thus this column is susceptible to microseconds rounding errors from around year 2250. In contrast, in Postgres v14 EXTRACT returns numeric type which effectively has arbitrary precision for this use case';

CREATE TABLE participant_user_rights (
    user_internal_id    INTEGER         NOT NULL REFERENCES participant_users (internal_id) ON DELETE CASCADE,
    user_right          INTEGER         NOT NULL,
    for_party           VARCHAR(512)    NOT NULL,
    granted_at          BIGINT          NOT NULL DEFAULT (1000 * 1000 * EXTRACT(epoch FROM CURRENT_TIMESTAMP))::bigint,
    UNIQUE (user_internal_id, user_right, for_party)
);
COMMENT ON COLUMN participant_user_rights.granted_at IS
    'Up to and including Postgres v13 EXTRACT function returns double type and thus this column is susceptible to microseconds rounding errors from around year 2250. In contrast, in Postgres v14 EXTRACT returns numeric type which effectively has arbitrary precision for this use case';

INSERT INTO participant_users(user_id, primary_party) VALUES ('participant_admin', '!');
INSERT INTO participant_user_rights(user_internal_id, user_right, for_party)
    SELECT internal_id, 1, '!'
    FROM participant_users
    WHERE user_id = 'participant_admin';