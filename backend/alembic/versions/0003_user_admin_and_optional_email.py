"""Make user email optional and add admin flag.

Revision ID: 0003_user_admin_and_optional_email
Revises: 0002_optional_postgis_geom
Create Date: 2026-02-01

"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision = "0003_user_admin_and_optional_email"
down_revision = "0002_optional_postgis_geom"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # SQLite cannot ALTER COLUMN nullability directly; batch_alter_table recreates the table.
    with op.batch_alter_table("users") as batch_op:
        batch_op.alter_column("email", existing_type=sa.Text(), nullable=True)
        batch_op.add_column(
            sa.Column(
                "is_admin",
                sa.Boolean(),
                nullable=False,
                server_default=sa.text("FALSE"),
            )
        )


def downgrade() -> None:
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("is_admin")
        batch_op.alter_column("email", existing_type=sa.Text(), nullable=False)
