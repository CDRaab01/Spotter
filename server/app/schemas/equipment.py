from typing import Annotated, Literal

from pydantic import BaseModel, Field, model_validator

from app.limits import (
    INVENTORY_BAR_MAX,
    INVENTORY_DUMBBELL_MAX,
    INVENTORY_MAX_BARS,
    INVENTORY_MAX_DUMBBELLS,
    INVENTORY_MAX_PAIRS,
    INVENTORY_MAX_PLATE_SIZES,
    INVENTORY_PLATE_MAX,
    INVENTORY_STACK_STEP_MAX,
)
from app.loading import Inventory, Plate


class PlateIn(BaseModel):
    """One plate size and how many matching **pairs** of it the user owns. Pairs, because a
    barbell takes one on each side; one-sided loading can use both plates of a pair."""

    weight: float = Field(gt=0, le=INVENTORY_PLATE_MAX)
    pairs: int = Field(ge=0, le=INVENTORY_MAX_PAIRS)


class EquipmentInventory(BaseModel):
    """What the user can load, in ``unit`` (plates are stamped in lb or kg, so the inventory keeps
    its own unit rather than converting them to the server's canonical pounds).

    Normalised on the way in — values rounded to 0.01, duplicates merged, zero-pair plates dropped,
    lists sorted — so the stored shape is canonical whatever the client sent.
    """

    unit: Literal["lb", "kg"] = "lb"
    bars: list[Annotated[float, Field(gt=0, le=INVENTORY_BAR_MAX)]] = Field(
        default_factory=list, max_length=INVENTORY_MAX_BARS
    )
    plates: list[PlateIn] = Field(default_factory=list, max_length=INVENTORY_MAX_PLATE_SIZES)
    dumbbells: list[Annotated[float, Field(gt=0, le=INVENTORY_DUMBBELL_MAX)]] = Field(
        default_factory=list, max_length=INVENTORY_MAX_DUMBBELLS
    )
    # Selectorized machine / cable pin step. None = no stack equipment.
    stack_step: float | None = Field(default=None, gt=0, le=INVENTORY_STACK_STEP_MAX)

    @model_validator(mode="after")
    def _normalise(self) -> "EquipmentInventory":
        self.bars = sorted({round(b, 2) for b in self.bars}, reverse=True)
        pairs: dict[float, int] = {}
        for p in self.plates:
            w = round(p.weight, 2)
            pairs[w] = min(INVENTORY_MAX_PAIRS, pairs.get(w, 0) + p.pairs)
        self.plates = [
            PlateIn(weight=w, pairs=n) for w, n in sorted(pairs.items(), reverse=True) if n > 0
        ]
        self.dumbbells = sorted({round(d, 2) for d in self.dumbbells})
        if self.stack_step is not None:
            self.stack_step = round(self.stack_step, 2)
        return self

    def to_inventory(self) -> Inventory:
        return Inventory(
            unit=self.unit,
            bars=tuple(self.bars),
            plates=tuple(Plate(p.weight, p.pairs) for p in self.plates),
            dumbbells=tuple(self.dumbbells),
            stack_step=self.stack_step,
        )

    @classmethod
    def from_inventory(cls, inv: Inventory) -> "EquipmentInventory":
        return cls(
            unit=inv.unit,
            bars=list(inv.bars),
            plates=[PlateIn(weight=p.weight, pairs=p.pairs) for p in inv.plates],
            dumbbells=list(inv.dumbbells),
            stack_step=inv.stack_step,
        )


class EquipmentOut(BaseModel):
    """``configured`` is false until the user saves an inventory; ``inventory`` is then the
    standard-gym default the server is assuming, so the client can show (and start editing from)
    exactly what suggestions are being snapped to."""

    configured: bool
    inventory: EquipmentInventory
