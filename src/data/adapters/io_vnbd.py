"""IO-VNBD adapter for the first prototype.

Implement this adapter first. PPC and UrbanNav adapters should be added only
after the complete IO-VNBD replay pipeline is working.
"""


class IOVNBDAdapter:
    """Load and standardize one IO-VNBD trip."""

    def load_trip(self, trip_path):
        raise NotImplementedError("Map IO-VNBD files to the canonical schema")

