from pathlib import Path

from scripts.write_io_vnbd_acquisition_report import LOCKED, inventory_paths, trip_name


def test_inventory_paths_counts_files_and_extensions(tmp_path):
    (tmp_path / "S-test.csv").write_text("x", encoding="utf-8")
    (tmp_path / "photo.JPG").write_text("x", encoding="utf-8")
    result = inventory_paths(tmp_path)
    assert result["file_count"] == 2
    assert result["csv_count"] == 1
    assert result["extensions"][".csv"] == 1


def test_trip_name_and_locked_exclusion_contract():
    assert trip_name(Path("S-S1.csv")) == "s1"
    assert trip_name(Path("V-Vfa01.csv")) == "vfa01"
    assert LOCKED == {"vta1a", "vta1b", "y1"}


def test_report_writer_is_local_and_does_not_require_web_access():
    source = Path("scripts/write_io_vnbd_acquisition_report.py").read_text(encoding="utf-8")
    assert "web" not in source.lower()
    assert "io_vnbd_acquisition_inventory.json" in source
