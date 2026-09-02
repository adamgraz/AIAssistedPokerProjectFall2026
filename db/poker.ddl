create table if not exists profiles
(
    ProfileId      INTEGER       not null
        primary key autoincrement,
    PlayerUuid     NVARCHAR(36)  not null,
    DisplayName    NVARCHAR(40)  not null,
    PassphraseHash NVARCHAR(255) not null,
    HandsPlayed    INTEGER       not null default 0,
    NetChips       INTEGER       not null default 0,
    CreatedAt      DATETIME      not null default current_timestamp
);

create unique index if not exists IX_ProfilesPlayerUuid on profiles (PlayerUuid);

-- Live-session crash recovery, not lifetime stats (that's profiles above). One row per
-- currently-seated player, overwritten whole after every hand and every rebuy - never
-- incremented, so a crash mid-write can never leave a half-applied delta. Rows older than
-- the app's own staleness window are never rehydrated on restart (see DB layer), and every
-- row is deleted the moment its player actually leaves or the table is closed - this is
-- scratch space for "what to restore Table to," never a second permanent ledger.
create table if not exists table_state
(
    PlayerUuid  NVARCHAR(36) not null
        primary key,
    DisplayName NVARCHAR(40) not null,
    Stack       INTEGER      not null,
    TotalBuyIn  INTEGER      not null,
    UpdatedAt   DATETIME     not null default current_timestamp
);
