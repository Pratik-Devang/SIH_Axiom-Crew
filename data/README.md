# Data

This folder holds all raw, interim, and processed trip data used for training and evaluation.

```text
data/
├── raw/          Original dataset files. Never edit these.
├── interim/      Synchronized or partially cleaned files.
├── processed/    Standardized one-trip-per-file Parquet data.
├── external/     Optional PPC and UrbanNav files for later validation.
├── manifests/    Dataset inventory, sensor details and quality notes.
└── splits/       Trip-level train, validation and test assignments.
```

The initial prototype uses IO-VNBD only. PPC and UrbanNav adapters belong to the post-hackathon validation phase.

## Standard Processed Schema

Each processed trip Parquet file contains these required fields:

```text
timestamp
accel_x, accel_y, accel_z
gyro_x, gyro_y, gyro_z
latitude, longitude
east, north
speed_reference
heading_reference
gnss_available
trip_id
dataset_name
```

Reference fields (`speed_reference`, `heading_reference`, `gnss_available`) are used for training and evaluation only. They must **not** be exposed to the navigation estimator during a simulated GNSS outage.

## Split Convention

Complete trip families are assigned to exactly one of `train`, `validation`, or `test` in `splits/io_vnbd_splits.yaml`. No single trip appears in more than one split — this prevents data leakage between the ML training and evaluation phases.
