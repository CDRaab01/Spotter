"""persistent server-side training profile on users

The training profile (equipment, experience, goal, age group, limitations) used
to live only in the Android client's DataStore, written only by the onboarding
questionnaire, and reached the coach only as the client-supplied, untrusted
`user_context` string. Most users never see onboarding, so the profile was empty
and the coach re-asked "what equipment do you have?" every conversation. Storing
it on the user makes it trusted, server-derived context on every AI call.

`profile_updated_at` is stamped whenever any profile field is written, so a
client can tell a never-filled profile from a deliberately cleared one.

This also **drops the dead `settings` column**. It has existed since 0001 and has
never been read anywhere — its only reference was `reset_account` setting it to
None. Adding real profile columns beside a dead one is exactly the drift this repo
dislikes, so it goes in the same migration. The downgrade re-adds it nullable
(no data to restore — there never was any).

Revision ID: 0016
Revises: 0015
Create Date: 2026-07-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0016"
down_revision = "0015"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("users", sa.Column("equipment", sa.String(255), nullable=True))
    op.add_column("users", sa.Column("experience", sa.String(32), nullable=True))
    op.add_column("users", sa.Column("goal", sa.String(32), nullable=True))
    op.add_column("users", sa.Column("age_group", sa.String(32), nullable=True))
    op.add_column("users", sa.Column("limitations", sa.Text(), nullable=True))
    op.add_column(
        "users",
        sa.Column("profile_updated_at", sa.DateTime(timezone=True), nullable=True),
    )

    op.drop_column("users", "settings")


def downgrade() -> None:
    op.add_column("users", sa.Column("settings", sa.Text(), nullable=True))

    op.drop_column("users", "profile_updated_at")
    op.drop_column("users", "limitations")
    op.drop_column("users", "age_group")
    op.drop_column("users", "goal")
    op.drop_column("users", "experience")
    op.drop_column("users", "equipment")
