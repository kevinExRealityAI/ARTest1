# Project

Packing & Moving Survey

Current experiment:

Automatic object dimension estimation using Google ARCore Depth API.

The objective is NOT to build a perfect scanner.

The objective is to build the smallest amount of software capable of accurately estimating an object's

- Length
- Width
- Height

while maintaining smooth AR performance.

---

# Philosophy

Prefer simple solutions.

Avoid introducing mathematical complexity unless it provides measurable accuracy improvements.

Do not implement algorithms simply because they are academically interesting.

Every added component must justify itself.

---

# Technical Stack

- Kotlin
- ARCore
- Coroutines
- Android
- No TensorFlow
- No MediaPipe
- No OpenCV unless absolutely necessary

Use only:

- ARCore Depth API
- Plane Detection
- Camera Pose
- Point Clouds

---

# Desired User Experience

User opens scanner.

↓

Points camera at object.

↓

Moves camera slowly around object for several seconds.

↓

Application accumulates depth information.

↓

Bounding box stabilizes.

↓

Measurements appear.

User should never manually tap object corners.

---

# Desired Architecture

Camera

↓

Frame Provider

↓

Depth Acquisition

↓

Point Cloud Generator

↓

Object Isolation

↓

Temporal Accumulator

↓

Bounding Box

↓

Measurement Smoothing

↓

UI

Each stage should be independently testable.

---

# Performance

Maintain 30+ FPS.

Never perform expensive calculations on the rendering thread.

Heavy calculations belong on Dispatchers.Default.

Reuse buffers whenever possible.

Avoid allocations inside per-frame loops.

---

# Measurement Strategy

Always prefer stable measurements over instant measurements.

Accumulate multiple frames.

Reject noisy frames.

Only publish dimensions after stabilization.

---

# Quality Metrics

Every measurement should include

- confidence
- visible coverage
- timestamp

Example

Length: 82 cm

Confidence: High

Coverage: 88%

---

# Error Handling

Gracefully handle

- missing depth
- low confidence depth
- plane unavailable
- insufficient points
- tracking loss

Never crash because depth is unavailable.

---

# Code Style

Prefer many small classes.

Single responsibility.

Avoid giant utility classes.

Avoid premature optimization.

Document every mathematical step.

Explain why an algorithm exists.

---

# Current Goal

Produce the simplest prototype capable of estimating object dimensions using ARCore Depth.

Complexity should only increase when accuracy requires it.
