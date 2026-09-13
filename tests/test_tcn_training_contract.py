import ast
from pathlib import Path

import src.ml.train as train


def test_run_ids_are_unique_and_never_reuse_directories(tmp_path, monkeypatch):
    monkeypatch.setattr(train, "ARTIFACTS", tmp_path)
    config = train.load_config()
    names = train.split_trip_names(config)
    first_id, first_dir, _ = train.create_unique_run(config, names)
    second_id, second_dir, _ = train.create_unique_run(config, names)
    assert first_id != second_id
    assert first_dir != second_dir
    assert first_dir.is_dir() and second_dir.is_dir()


def test_train_source_never_loads_or_constructs_test_dataset():
    source = Path(train.__file__).read_text(encoding="utf-8")
    ast.parse(source)
    assert "load_split_trips" not in source
    assert "test_ds" not in source
    assert "load_train_validation_trips" in source


def test_validation_metrics_source_contains_required_categories():
    source = Path(train.__file__).read_text(encoding="utf-8")
    for required in ("stationary", "speed_bins", "per_trip", "acceleration", "braking", "validation_metrics.json"):
        assert required in source


def test_normalization_is_fitted_only_from_train_split():
    source = Path(train.__file__).read_text(encoding="utf-8")
    assert 'fit_normalization(split_trips["train"]' in source
    assert 'fit_normalization(split_trips["test"]' not in source


def test_checkpoint_selection_uses_validation_mae():
    assert train.is_better_validation_mae(10.0, 11.0)
    assert not train.is_better_validation_mae(11.0, 10.0)
    source = Path(train.__file__).read_text(encoding="utf-8")
    assert "is_better_validation_mae(val_metrics[\"mae_kmh\"], best_mae_kmh)" in source
    assert "best_validation_loss_at_best_mae" in source
