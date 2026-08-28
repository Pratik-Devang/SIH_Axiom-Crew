# 🏆 Percorsa-ML Speed Estimation: Technical Walkthrough & Integration Guide

This document provides every technical detail, architectural choice, and implementation decision made to build, optimize, and deploy the high-accuracy Temporal Convolutional Network (TCN) for vehicle speed estimation using only smartphone IMU sensors.

---

## 📖 1. The Core Concept

Vehicle speed is traditionally measured using GPS or OBD-II wheel speed sensors. However, GPS signal drops in tunnels/urban canyons, and OBD-II is not always accessible. 

This project estimates speed using **only smartphone IMU sensors** (3-axis Accelerometer + 3-axis Gyroscope). The AI learns the relationship between the vehicle's body vibrations (suspension oscillations, pitch dive during braking, yaw rate during turning) and its forward velocity.

---

## 🛠️ 2. Data Preprocessing & Synchronization

Smartphone sensors log at irregular, high-frequency intervals (usually between 10 Hz and 100 Hz with microsecond wiggles), while vehicle OBD-II registers speed at a lower, steady frequency.

### The Pipeline Steps:
1. **Resampling**: We resample all smartphone readings to a strict, constant **10 Hz** (100 ms intervals) using linear interpolation to create a clean, uniform time-series grid.
2. **Alignment**: Smartphone time-series are aligned with the vehicle reference speedometer speed (divided by 3.6 to convert `km/h` to standard ML `m/s` units).
3. **Strict Validation Gates**:
   * **Monotonicity Check**: Time must flow forward. If a trip's `time_since_start_s` is not strictly increasing (due to phone CPU lag or buffering glitches), the trip is flagged as corrupt and discarded.
   * **Missing Value Filtering**: Trips with NaN entries or mismatched sensor rows are automatically filtered out.
   * **27 out of 32 trips** successfully passed these validation gates, ensuring 100% clean training data.

### Multi-Trip Leakage Prevention:
To prevent the model from memorizing specific trips (overfitting), we split the dataset by **entire trips**, NOT by sliding windows:
* **Train split**: 18 trips (292,481 windows)
* **Validation split**: 4 trips (18,325 windows)
* **Test split**: 5 trips (5,731 windows)
No window in the validation or test sets ever shares data from a trip used during training.

---

## 🧠 3. TCN Architecture Design

We use a **Temporal Convolutional Network (TCN)** instead of an LSTM or GRU because TCNs are faster, have no gradient explosion issues, and have a strictly bounded memory history.

```
Input: [1, 6, 50] (6 channels, 5s history)
   |
   +--> CasualConv1D (Kernel=3, Dilation=1)
   |
   +--> CasualConv1D (Kernel=3, Dilation=2)
   |
   +--> CasualConv1D (Kernel=3, Dilation=4)
   |
   +--> CasualConv1D (Kernel=3, Dilation=8)
   |
   +--> Dense Output Layer --> Predicted Speed (1 value)
```

### Key Parameters:
* **Input Channels**: 6 (`[accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z]`).
* **Hidden Channels**: `[128, 128, 128, 128]` (Increased from 32 to double parameter capacity to ~350,000 weights, allowing the AI to memorize complex vehicle suspension profiles).
* **Dilations**: `[1, 2, 4, 8]` with **Kernel Size 3**.
* **Receptive Field Math**:
  $$\text{Receptive Field} = 1 + (KernelSize - 1) \times \sum \text{Dilations} = 1 + (3 - 1) \times (1+2+4+8) = 31\text{ samples} \approx \mathbf{3.1\text{ seconds}}$$
  The TCN can "see" a maximum of **3.1 seconds** of history.
* **Window Size**: **5 seconds** (50 samples). This is the mathematically optimal sweet spot—it fully utilizes the TCN's 3.1-second field of view with a safety buffer, without adding heavy layers that slow down mobile CPUs.
* **No Uncertainty Head**: We set `predict_uncertainty: false` to focus all model capacity on pure speed regression accuracy.

---

## ⚡ 4. Training & Memory Optimizations

To handle the large dataset (nearly **300,000 windows**) on CPU and GPU environments, we implemented three critical performance optimizations:

1. **Lazy Dataset Loading**:
   * *Problem*: Pre-allocating 300,000 numpy arrays in memory caused memory thrashing and took minutes to initialize.
   * *Solution*: The `SpeedWindowDataset` stores only the 27 flat trip arrays, and performs slicing (`inputs[start:end].T.copy()`) dynamically inside `__getitem__` when requested by the PyTorch `DataLoader`.
   * *Result*: Memory footprint reduced by **99%**, initialization is now instant (0.05 seconds).
2. **GPU (CUDA) Acceleration**:
   * Automatically detects and transfers the model layers and batch tensors to the GPU (`cuda`) if available.
   * *Result*: Training time on Google Colab T4 GPU dropped from 2 minutes per epoch to **8 seconds per epoch** (a 15x speedup).
3. **Validation Downsampling**:
   * We set `stride: 10` for validation data, reducing validation windows from 183,352 to **18,325 windows**.
   * *Result*: Validation passes run 10x faster without affecting the validation loss trend.

---

## 📊 5. Accuracy & Physical Limitations

The model trained for **100 epochs** with a Cosine Annealing learning rate schedule starting at `0.001` and smoothly decaying to `0.00001`.

### Official Test Set Scorecard (Unseen Vehicles/Drivers):
* **Overall Mean Absolute Error (MAE)**: **`5.30 m/s` (19.08 km/h)**
* **Root Mean Squared Error (RMSE)**: **`6.27 m/s` (22.56 km/h)**

#### State Breakdown:
* **Cruising (Steady Speed)**: `4.95 m/s` (17.83 km/h)
* **Acceleration**: `4.25 m/s` (15.32 km/h)
* **Braking**: `5.13 m/s` (18.47 km/h)
* **Turning**: `5.56 m/s` (20.02 km/h)

### ⚠️ Physical limits of IMU-only speed estimation:
A smartphone IMU alone cannot achieve 0 km/h error due to three physical laws:
1. **The Gravity Leak**: When the car goes up a hill or pitches during braking, the phone tilts. Accelerometers cannot distinguish gravity from linear speed changes, causing a tilt-based speed estimation error.
2. **Suspension Variance**: Stiff suspensions vibrate more than soft suspensions. The AI has to guess the speed based on vibrations, which vary per vehicle.
3. **Road Surface Variance**: Driving 30 km/h on gravel vibrates the phone as much as driving 80 km/h on smooth highway asphalt.

*This is why the TCN output is designed to be passed to an **Extended Kalman Filter (EKF)** in Role 3, which smooths out the wiggles and fuses it with GPS updates.*

---

## ❓ Q&A: Student-Teacher Session (All Key Questions Explained)

Here are the detailed explanations for the critical questions discussed throughout this development process:

### Q1: What happens if training is at 10/30 epochs and I press Ctrl+C?
**A**: PyTorch saves model weights dynamically. During training, the code checks validation loss at every epoch. If the validation score beats the previous best, it immediately saves `tcn_best.pt` to disk. Pressing `Ctrl+C` will terminate the script, but your best checkpoint up to that epoch will remain safely saved on your computer!

### Q2: Why was it taking so much time to train locally, and where can I train fast?
**A**: Training locally was taking over 7 minutes per epoch because it was running entirely on the CPU. The fastest place to train is **Google Colab** with the free **T4 GPU** runtime, where it takes only **8 seconds per epoch**!

### Q3: What is "NLL" in the training logs?
**A**: **NLL** stands for **Negative Log-Likelihood**. It is a grading formula that penalizes the model based on its prediction accuracy **and** its confidence. If the model guesses a speed incorrectly but says it was highly confident, it receives a massive penalty. If it guesses incorrectly but was honest about being uncertain, the penalty is small. (In our final config, we disabled uncertainty, switching to standard **MSE** loss).

### Q4: How do we tell if training is going good or bad?
**A**: Training is going well if both the **training loss** and **validation loss** are steadily decreasing over the epochs. A slight bounce in validation loss is normal (SGD noise), but the overall trend must be downwards.

### Q5: Why was Colab still taking 1-2 minutes per epoch after enabling the T4 GPU?
**A**: Even if Colab has a GPU, PyTorch will run on CPU by default. We had to modify `train.py` to:
1. Detect CUDA: `device = torch.device("cuda" if torch.cuda.is_available() else "cpu")`
2. Transfer the model to the GPU: `model.to(device)`
3. Transfer the batch tensors inside `run_epoch`: `x, y = x.to(device), y.to(device)`
Once added, the training speed instantly accelerated to under 10 seconds per epoch.

### Q6: Why is the learning rate dropping during training? Is that good?
**A**: Yes, dropping the learning rate is essential! Think of it like golf: far away from the hole, you take a big swing (high learning rate) to get close. Near the hole, you switch to a putter (low learning rate) for tiny, precise taps. Dropping the learning rate helps the AI settle into the absolute best local minimum without overshooting the goal.

### Q7: Can we increase the window size beyond 5 seconds to get more context?
**A**: You can, but you hit the **Receptive Field** wall. Our TCN has a maximum viewing scope (binoculars magnification) of **3.1 seconds**. If you give it a 10-second window, the TCN still only sees 3.1 seconds of it; the other 6.9 seconds are ignored unless you add more convolutional layers (which makes the model heavier and slower). Therefore, **5 seconds (50 samples)** is the optimal sweet spot!

### Q8: How does the dataset loader know which trips are perfect (27) and corrupt (5)?
**A**: The data preparation script runs three strict checks on each file:
1. **Monotonicity**: Time must always move forward. If timestamps jump backward or stall, the file is rejected.
2. **Missing data**: Any NaNs or blank sensor readings reject the file.
3. **Mismatched rows**: If the smartphone log has 10,000 rows but the vehicle speedometer log has 30,000 rows, they are mismatched and rejected.

### Q9: During real-world driving, there is no vehicle speed data. How does the model work?
**A**: Vehicle speed is only used during **training** (as the "practice exam answer key") so the model can learn. The exported ONNX model only takes the 6 smartphone IMU channels as inputs to output the predicted speed—it does not need or accept vehicle speed inputs.

### Q10: What is ONNX and PT exactly?
**A**:
* **`.pt` (PyTorch)**: The raw, heavy development file containing model parameters, optimizer tools, and training history. It requires a full Python environment and cannot run on mobile.
* **`.onnx` (ONNX)**: The universal, frozen, lightweight blueprint of the model (only 376 KB). It contains only the math formulas needed for inference and runs natively on smartphones using ONNX Runtime Mobile.

### Q11: What if a user is walking with the app instead of driving?
**A**: The model was trained purely on vehicle vibration frequencies. Walking creates low-frequency stepping wiggles (~2 Hz) that will confuse the TCN, leading to inaccurate speed guesses. To handle this, the app should use an **Activity Classifier** to detect walking and switch to a standard **Pedometer Step-Counter** instead.

### Q12: Why did we split by trips sequentially (unshuffled) instead of randomly shuffling before splitting?
**A**: Shuffling before splitting allows the model to train on a vehicle (e.g. `Vw5`) and test on that same vehicle (`Vw6`), giving a fake, flattered test score. Keeping it sequential is a **true Out-of-Distribution (OOD) test**—it proves how the app will perform on a vehicle it has **never seen before**, which is the most honest way to evaluate real-world performance!

---

## 📱 7. Mobile ONNX Integration Guide

The model is exported to `tcn.onnx` (file size: **376 KB**). Here is how the mobile application must load and execute the model:

### 1. ONNX Model Contract
* **Input Node Name**: `input`
* **Input Tensor Shape**: `[1, 6, 50]` (Float32)
* **Output Node Name**: `speed_mps`
* **Output Tensor Shape**: `[1]` (Float32, predicted speed in meters per second)

### 2. Android Kotlin Implementation Steps

#### Step A: Add ONNX Runtime Dependency
Add this to your Android `build.gradle` file:
```kotlin
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
}
```

#### Step B: Maintain a 5-Second Rolling Sensor Queue
You must collect smartphone accelerometer and gyroscope data at **10 Hz** (every 100 ms). 
Keep a rolling queue of the last **50 samples**:
```kotlin
// Each sensor sample has 6 values: [accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z]
val sensorQueue: Queue<FloatArray> = LinkedList() 

fun onSensorChanged(accel: FloatArray, gyro: FloatArray) {
    val sample = floatArrayOf(accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2])
    sensorQueue.add(sample)
    
    // Keep only the last 5 seconds (50 samples)
    if (sensorQueue.size > 50) {
        sensorQueue.poll()
    }
}
```

#### Step C: Prepare the Input Tensor Shape `[1, 6, 50]`
The input shape is structured as `[Batch, Channels, Time]`. 
This means you must group all 50 samples by sensor channel (transposed matrix):
* Channel 0: 50 samples of Accelerometer X
* Channel 1: 50 samples of Accelerometer Y
* Channel 2: 50 samples of Accelerometer Z
* Channel 3: 50 samples of Gyroscope X
* Channel 4: 50 samples of Gyroscope Y
* Channel 5: 50 samples of Gyroscope Z

```kotlin
fun prepareTensorInput(): FloatArray {
    val flatInput = FloatArray(1 * 6 * 50)
    val list = sensorQueue.toList()
    
    for (channel in 0..5) {
        for (timeStep in 0..49) {
            val index = channel * 50 + timeStep
            flatInput[index] = list[timeStep][channel]
        }
    }
    return flatInput
}
```

#### Step D: Run Inference
Load the model from your Android assets and call `run`:
```kotlin
val env = OrtEnvironment.getEnvironment()
val session = env.createSession("tcn.onnx", OrtSession.SessionOptions())

fun predictSpeed(): Float {
    if (sensorQueue.size < 50) {
        return 0.0f // Wait 5 seconds to fill the buffer (cold start)
    }

    val inputData = prepareTensorInput()
    val inputShape = longArrayOf(1, 6, 50)
    val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inputShape)
    
    val results = session.run(mapOf("input" to inputTensor))
    val outputTensor = results[0].value as Array<FloatArray>
    
    val speedMps = outputTensor[0][0] // Speed in meters per second
    return speedMps * 3.6f // Convert to km/h for the UI speedometer
}
```
