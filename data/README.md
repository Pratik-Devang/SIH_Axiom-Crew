# Data layout

```text
data/
├── raw/          Original dataset files. Never edit these.
├── interim/      Synchronized or partially cleaned files.
├── processed/    Standardized one-trip-per-file Parquet data.
├── external/     Optional PPC and UrbanNav files for later validation.
├── manifests/    Dataset inventory, sensor details and quality notes.
└── splits/       Trip-level train, validation and test assignments.
```

The initial prototype should use IO-VNBD only. PPC and UrbanNav adapters belong
to the post-hackathon validation phase.

## Standard processed schema

Required fields:

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

Reference fields are used for training and evaluation. They must not be exposed
to the navigation estimator during a simulated GNSS outage.

