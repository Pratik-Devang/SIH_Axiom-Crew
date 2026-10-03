# IO-VNBD Dataset Audit

{
  "dataset_size": {
    "total_trips": 32,
    "total_samples": 766749,
    "train_samples": 532441,
    "train_windows_stride1": 531167,
    "usable_duration_s": 76671.70000000545
  },
  "quality": {
    "nan_inf": 0,
    "missing_channels": [],
    "timestamp_duplicates": 0,
    "timestamp_non_monotonic": 0,
    "timestamp_gaps_over_0_2s": 0
  },
  "split": {
    "train": [
      "S1",
      "S2",
      "S3a",
      "S3b",
      "S3c",
      "S4",
      "Vw1",
      "Vw2",
      "Vw3",
      "Vw4",
      "Vw5",
      "Vw6",
      "Vw7",
      "Vw8",
      "Vw9",
      "Vw10",
      "Vw11",
      "Vw12",
      "Vw13",
      "Vw14a",
      "Vw14b",
      "Vw14c",
      "Vw15",
      "Vw16a",
      "Vw16b",
      "Vw17"
    ],
    "validation": [
      "M",
      "Vfa01",
      "Vfa02"
    ],
    "test": [
      "Vta1a",
      "Vta1b",
      "Y1"
    ]
  },
  "frame_contract": {
    "channels": [
      "accel_x",
      "accel_y",
      "accel_z",
      "gyro_x",
      "gyro_y",
      "gyro_z"
    ],
    "semantic_order": [
      "forward_accel",
      "lateral_accel",
      "up_accel",
      "gyro_forward",
      "gyro_left",
      "gyro_up"
    ],
    "units": {
      "accel": "m/s^2",
      "gyro": "rad/s"
    },
    "gravity_included": true,
    "vehicle_frame_transform_verified": false,
    "reason": "processed CSVs contain vehicle-aligned fields but raw transform provenance is not encoded per row"
  },
  "label_audit": {
    "vehicle_speed_source": "Indicated Vehicle Speed (km/hr), converted to vehicle_speed in preparation",
    "independent_reference_comparison": "not performed; no independent reference field was assumed"
  }
}