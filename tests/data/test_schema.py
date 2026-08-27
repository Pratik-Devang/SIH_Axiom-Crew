from src.data.schema import REQUIRED_COLUMNS


def test_canonical_schema_has_unique_columns():
    assert len(REQUIRED_COLUMNS) == len(set(REQUIRED_COLUMNS))

