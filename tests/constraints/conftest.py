"""
conftest.py
===========
Shared pytest fixtures for Percorsa Role 4 tests.

Fixtures
--------
- ``chembur_graph``   : loads the Chembur 1km graph once per session.
- ``osm_loader``      : OSMLoader instance with the Chembur graph.
- ``candidate_gen``   : CandidateGenerator with index pre-built.
- ``chembur_origin``  : ENU frame origin for the Chembur fixture.
- ``chembur_points``  : list of (lat, lon, heading) test positions on known roads.

Note: if the GraphML file doesn't exist yet, the graph-dependent fixtures
will be skipped with a clear message (run the bootstrap script first).
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import pytest

logging.basicConfig(level=logging.DEBUG)

GRAPHML_PATH = Path("data/chembur_1km.graphml")
CONFIG_PATH = "configs/role4.yaml"

# Known positions in Chembur, Mumbai that should be on drivable roads
CHEMBUR_TEST_POINTS = [
    # (lat, lon, approx_heading_deg, description)
    (19.0510, 72.8940, 90.0,  "Chembur centre eastbound"),
    (19.0520, 72.8950, 0.0,   "Chembur centre northbound"),
    (19.0505, 72.8930, 270.0, "Chembur centre westbound"),
]


def pytest_configure(config):
    """Register custom markers."""
    config.addinivalue_line(
        "markers",
        "requires_graph: mark test as requiring the Chembur GraphML fixture",
    )


@pytest.fixture(scope="session")
def osm_loader():
    """OSMLoader with Chembur graph loaded (session-scoped — loads once)."""
    if not GRAPHML_PATH.exists():
        pytest.skip(
            f"Chembur GraphML not found at {GRAPHML_PATH}. "
            "Run scripts/bootstrap_chembur.py to download it."
        )
    from src.maps.osm_loader import OSMLoader
    loader = OSMLoader(config_path=CONFIG_PATH)
    loader.load_from_graphml(GRAPHML_PATH)
    return loader


@pytest.fixture(scope="session")
def candidate_gen(osm_loader):
    """CandidateGenerator with KD-tree index built over Chembur graph."""
    from src.maps.candidates import CandidateGenerator
    gen = CandidateGenerator(osm_loader, config_path=CONFIG_PATH)
    gen.build_index()
    return gen


@pytest.fixture(scope="session")
def chembur_origin():
    """ENU frame origin for the Chembur test fixture."""
    from src.preprocessing.coordinates import LatLonOrigin
    return LatLonOrigin(lat=19.051, lon=72.894)


@pytest.fixture
def chembur_points():
    """List of (lat, lon, heading_deg, description) test positions."""
    return CHEMBUR_TEST_POINTS


@pytest.fixture
def gnss_trust_manager(tmp_path):
    """GNSSTrustManager with log output to a temp file."""
    from src.constraints.gnss_trust import GNSSTrustManager
    return GNSSTrustManager(
        config_path=CONFIG_PATH,
        event_log_path=tmp_path / "gnss_events.jsonl",
    )


@pytest.fixture
def stop_detector(tmp_path):
    """StopDetector with log output to a temp file."""
    from src.constraints.stop_detection import StopDetector
    return StopDetector(
        config_path=CONFIG_PATH,
        event_log_path=tmp_path / "stop_events.jsonl",
    )


@pytest.fixture
def vehicle_constraint_detector(tmp_path):
    """VehicleConstraintDetector with log output to a temp file."""
    from src.constraints.vehicle import VehicleConstraintDetector
    return VehicleConstraintDetector(
        config_path=CONFIG_PATH,
        event_log_path=tmp_path / "nhc_events.jsonl",
    )
