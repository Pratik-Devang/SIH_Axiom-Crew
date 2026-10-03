import numpy as np
from scripts.audit_io_vnbd_dataset import motion_state, speed_bin

def test_speed_bins_are_deterministic():
    assert [speed_bin(x) for x in [0, 5, 20, 40, 60, 80, 100]] == ["0-5", "5-20", "20-40", "40-60", "60-80", "80-plus", "80-plus"]

def test_motion_state_uses_only_speed_derivative():
    result = motion_state(np.array([0.0, 0.0, 2.0, 2.0, 1.0]))
    assert result[0] == "stationary"
    assert "acceleration" in result
    assert "braking" in result
