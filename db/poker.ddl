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
