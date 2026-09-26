"""The user's equipment inventory (``users.equipment_inventory``) as the loading model sees it."""

import uuid

from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.loading import DEFAULT_INVENTORY_LB, Inventory
from app.models.user import User
from app.schemas.equipment import EquipmentInventory, EquipmentOut


def parse_inventory(raw: dict | None) -> Inventory | None:
    """The stored JSON as an :class:`Inventory`, or None when unset. A stored value that no
    longer validates (bounds tightened since it was written) reads as unset rather than 500ing
    every suggestion — the user sees the default and can re-save."""
    if not raw:
        return None
    try:
        return EquipmentInventory.model_validate(raw).to_inventory()
    except ValidationError:
        return None


def equipment_out(raw: dict | None) -> EquipmentOut:
    inv = parse_inventory(raw)
    return EquipmentOut(
        configured=inv is not None,
        inventory=EquipmentInventory.from_inventory(inv or DEFAULT_INVENTORY_LB),
    )


async def load_inventory(db: AsyncSession, user_id: uuid.UUID) -> Inventory | None:
    """The user's saved inventory, or None (= the standard-gym default, see app/loading.py)."""
    result = await db.execute(select(User.equipment_inventory).where(User.id == user_id))
    return parse_inventory(result.scalar_one_or_none())
