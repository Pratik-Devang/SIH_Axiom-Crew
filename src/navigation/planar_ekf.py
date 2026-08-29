"""Small planar EKF used by the hackathon replay pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


def wrap_angle(angle: float) -> float:
    """Wrap radians to [-pi, pi)."""
    return float((angle + np.pi) % (2.0 * np.pi) - np.pi)


@dataclass
class PlanarEkfConfig:
    acceleration_noise: float = 1.5
    yaw_rate_noise: float = 0.08
    speed_measurement_noise: float = 1.0
    gnss_position_noise: float = 4.0
    gnss_nis_threshold: float = 13.815  # chi-square, 2 DoF, 99.9%
    zupt_speed_noise: float = 0.05


@dataclass
class PlanarEkf:
    """EKF with state ``[east_m, north_m, speed_mps, heading_rad]``."""

    state: np.ndarray
    covariance: np.ndarray = field(
        default_factory=lambda: np.diag([4.0, 4.0, 2.0, 0.2])
    )
    config: PlanarEkfConfig = field(default_factory=PlanarEkfConfig)

    def __post_init__(self) -> None:
        self.state = np.asarray(self.state, dtype=float).reshape(4)
        self.covariance = np.asarray(self.covariance, dtype=float).reshape(4, 4)

    def predict(
        self, dt: float, yaw_rate_rps: float = 0.0, acceleration_mps2: float = 0.0
    ) -> None:
        if not np.isfinite(dt) or dt <= 0.0:
            raise ValueError("dt must be finite and positive")
        dt = min(float(dt), 1.0)
        east, north, speed, heading = self.state
        speed_next = max(0.0, speed + float(acceleration_mps2) * dt)
        heading_next = wrap_angle(heading + float(yaw_rate_rps) * dt)
        self.state = np.array(
            [
                east + speed_next * np.sin(heading_next) * dt,
                north + speed_next * np.cos(heading_next) * dt,
                speed_next,
                heading_next,
            ]
        )

        f = np.eye(4)
        f[0, 2] = np.sin(heading_next) * dt
        f[0, 3] = speed_next * np.cos(heading_next) * dt
        f[1, 2] = np.cos(heading_next) * dt
        f[1, 3] = -speed_next * np.sin(heading_next) * dt
        q = np.diag(
            [
                0.25 * self.config.acceleration_noise**2 * dt**4,
                0.25 * self.config.acceleration_noise**2 * dt**4,
                self.config.acceleration_noise**2 * dt**2,
                self.config.yaw_rate_noise**2 * dt**2,
            ]
        )
        self.covariance = f @ self.covariance @ f.T + q

    def _update(
        self,
        measurement: np.ndarray,
        h: np.ndarray,
        r: np.ndarray,
        max_nis: float | None = None,
    ) -> bool:
        innovation = measurement - h @ self.state
        s = h @ self.covariance @ h.T + r
        nis = float(innovation.T @ np.linalg.solve(s, innovation))
        if max_nis is not None and (not np.isfinite(nis) or nis > max_nis):
            return False
        gain = self.covariance @ h.T @ np.linalg.inv(s)
        self.state = self.state + gain @ innovation
        self.state[2] = max(0.0, self.state[2])
        self.state[3] = wrap_angle(self.state[3])
        identity = np.eye(4)
        residual = identity - gain @ h
        self.covariance = residual @ self.covariance @ residual.T + gain @ r @ gain.T
        return True

    def update_speed(self, speed_mps: float, variance: float | None = None) -> bool:
        if not np.isfinite(speed_mps):
            return False
        h = np.array([[0.0, 0.0, 1.0, 0.0]])
        noise = (
            self.config.speed_measurement_noise**2
            if variance is None
            else max(float(variance), 1e-6)
        )
        return self._update(
            np.array([max(0.0, speed_mps)]), h, np.array([[noise]])
        )

    def apply_zero_velocity(self, confidence: float = 1.0) -> bool:
        """Apply a confidence-weighted zero-forward-speed constraint."""
        confidence = float(np.clip(confidence, 1e-3, 1.0))
        variance = self.config.zupt_speed_noise**2 / confidence
        return self.update_speed(0.0, variance=variance)

    def update_gnss(
        self,
        east_m: float,
        north_m: float,
        accuracy_m: float | None = None,
        nis_threshold: float | None = None,
    ) -> bool:
        if not np.all(np.isfinite([east_m, north_m])):
            return False
        sigma = (
            self.config.gnss_position_noise
            if accuracy_m is None
            else max(float(accuracy_m), 1.0)
        )
        h = np.array([[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0]])
        threshold = (
            self.config.gnss_nis_threshold
            if nis_threshold is None
            else nis_threshold
        )
        return self._update(
            np.array([east_m, north_m]),
            h,
            np.eye(2) * sigma**2,
            max_nis=threshold,
        )

    @property
    def horizontal_uncertainty_m(self) -> float:
        return float(
            2.0 * np.sqrt(max(self.covariance[0, 0] + self.covariance[1, 1], 0.0))
        )
