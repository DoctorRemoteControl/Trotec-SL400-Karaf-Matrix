# SL400 for Apache Karaf

SL400 is an OSGi/Karaf-based integration for the **Trotec SL400 sound level meter**.
It reads live dB values from the device over serial, calculates rolling acoustic metrics, stores incidents, captures short audio clips, analyzes audio hints, and publishes alerts and reports to **Matrix**.

This project is split into small OSGi bundles so the serial input, runtime logic, storage, reporting, and Matrix integration stay modular and replaceable.

---

## Features

* Read SL400 measurements from a serial port
* Decode device frames into structured samples
* Calculate rolling metrics:

  * live dB
  * LAeq 1 min
  * LAeq 5 min
  * LAeq 15 min
  * max 1 min
* Trigger alerts with hysteresis and configurable send modes
* Store incidents in JSONL
* Capture audio clips around alert events
* Generate simple audio hints such as:

  * voices / crowd
  * wind / rumble
  * bass-heavy music
  * mechanical / hum
* Send alerts, status updates, clips, files, and images to Matrix
* Accept Matrix commands for querying and changing configuration
* Generate daily summaries, JSON exports, and incident graphs
* Package the whole stack as a Karaf feature

---

## Module Overview

### `sl400-api`

Public API bundle shared by the other modules.

Contains:

* data records such as `Sl400Sample`, `AcousticMetrics`, `IncidentRecord`
* service interfaces such as:

  * `Sl400Source`
  * `IncidentRepository`
  * `MatrixPublisher`
  * `AlertConfigService`
  * `MatrixConfigService`
  * `LiveStateService`
  * `AudioStatusService`

Use this module if you want to implement your own storage, transport, or source bundle.

---

### `sl400-core`

Pure logic bundle.

Contains:

* `Sl400Decoder` for turning raw SL400 serial frames into samples
* `AcousticMetricsEngine` for rolling LAeq/max calculations
* `AlarmEvaluator` for threshold logic with hysteresis and interval control

This bundle has no OSGi DS components by itself. It is intended to be reused by runtime bundles.

---

### `sl400-serial-jserialcomm`

Serial source implementation using `jSerialComm`.

Provides:

* `Sl400Source`

Reads data from the configured serial port, decodes frames through `Sl400Decoder`, and forwards samples into the runtime pipeline.

Config PID:

* `de.drremote.trotecsl400.serial`

---

### `sl400-storage-json`

JSONL-based storage implementation.

Provides:

* `IncidentRepository`

Stores incidents in a line-based JSON file and supports:

* append new incidents
* query by time range
* lookup by incident id
* attach clip path
* attach audio hint
* mark Matrix upload state
* cleanup old incidents and clip files

Config PID:

* `de.drremote.trotecsl400.storage`

---

### `sl400-matrix-http`

Outgoing Matrix sender.

Provides:

* `MatrixPublisher`

Handles:

* text messages
* alert events
* status messages
* file uploads
* image uploads
* audio uploads
* clip reuse via existing MXC URL

This bundle performs direct Matrix HTTP API calls with OkHttp.

---

### `sl400-matrix-sync`

Matrix command parsing and sync client helpers.

Contains:

* `MatrixSyncClient`
* `MatrixCommandProcessor`

Supports commands such as:

* `!sl400 help`
* `!sl400 status`
* `!sl400 set threshold 70`
* `!sl400 incidents since 30m`
* `!sl400 summary today`
* `!sl400 json yesterday`
* `!sl400 report now`
* `!sl400 graph since 6h`
* `!sl400 clip last`
* `!sl400 audio status`

---

### `sl400-report`

Reporting and export helpers.

Contains:

* `SummaryFormatter`
* `JsonExporter`
* `IncidentGraphRenderer`

Used by the runtime to generate:

* human-readable summaries
* JSON exports
* PNG incident graphs

---

### `sl400-runtime`

Main orchestration bundle.

Provides and coordinates:

* live state updates
* alert evaluation
* Matrix alert sending
* audio clip capture
* audio hint analysis
* Matrix status publishing
* Matrix command handling
* daily reports
* ConfigAdmin-backed services for alert and Matrix config

Important services:

* `AlertConfigService`
* `MatrixConfigService`
* `LiveStateService`
* `AudioStatusService`

Config PIDs:

* `de.drremote.trotecsl400.alert`
* `de.drremote.trotecsl400.matrix`
* `de.drremote.trotecsl400.audio`

---

### `sl400-features`

Karaf feature descriptor bundle.

Defines features:

* `sl400-thirdparty`
* `sl400`

This is the module you add to Karaf via `feature:repo-add`.

---

## Architecture

```text
SL400 device
   -> serial port
   -> sl400-serial-jserialcomm
   -> sl400-core decoder + metrics
   -> sl400-runtime
      -> alert evaluation
      -> JSON incident storage
      -> audio capture / hint analysis
      -> Matrix publisher
      -> reports / graphs / exports
```

---

## Build

Build everything from the parent project:

```bash
mvn clean install
```

If you deploy to your Maven repository:

```bash
mvn clean deploy
```

---


## Installation in Karaf

Before installing this project, first prepare your Karaf base setup as described here:

`https://github.com/DoctorRemoteControl/drremote-karaf-setup`

That repository contains the required base setup for Karaf, Maven repositories, and common DrRemote feature repositories.

### Add the SL400 feature repository

```bash
feature:repo-add mvn:de.drremote.trotecsl400/sl400-features/0.1.0-SNAPSHOT/xml/features
````

### Install the SL400 feature

```bash
feature:install sl400
```

### Verify the installation

```bash
feature:repo-list | grep sl400
feature:list | grep sl400
bundle:list | grep trotecsl400
```

---




## Karaf Configuration

## Serial

PID:

* `de.drremote.trotecsl400.serial`

```bash
config:edit de.drremote.trotecsl400.serial
config:property-set port /dev/ttyUSB0
config:property-set baudRate 9600
config:property-set dataBits 8
config:property-set stopBits 1
config:property-set parity NONE
config:property-set readTimeoutMs 1000
config:property-set reconnectDelayMs 5000
config:property-set enabled true
config:update
```

---

## Storage

PID:

* `de.drremote.trotecsl400.storage`

```bash
config:edit de.drremote.trotecsl400.storage
config:property-set baseDir ${karaf.data}/sl400
config:property-set fileName incidents.jsonl
config:update
```

---

## Matrix

PID:

* `de.drremote.trotecsl400.matrix`

```bash
config:edit de.drremote.trotecsl400.matrix
config:property-set homeserverBaseUrl https://matrix.example.org
config:property-set accessToken syt_xxxxxxxxxxxxxxxxx
config:property-set roomId !alerts:matrix.example.org
config:property-set deviceId sl400-node-01
config:property-set enabled true
config:update
```

---

## Alerting

PID:

* `de.drremote.trotecsl400.alert`

```bash
config:edit de.drremote.trotecsl400.alert
config:property-set enabled true
config:property-set thresholdDb 70.0
config:property-set hysteresisDb 2.0
config:property-set minSendIntervalMs 60000
config:property-set sendMode CROSSING_ONLY
config:property-set metricMode LAEQ_5_MIN
config:property-set allowedSendersCsv @operator:matrix.example.org
config:property-set commandRoomId !commands:matrix.example.org
config:property-set targetRoomId !alerts:matrix.example.org
config:property-set alertHintFollowupEnabled true
config:property-set dailyReportEnabled false
config:property-set dailyReportHour 9
config:property-set dailyReportMinute 0
config:property-set dailyReportRoomId !reports:matrix.example.org
config:property-set dailyReportJsonEnabled true
config:property-set dailyReportGraphEnabled true
config:update
```

---

## Audio Capture

PID:

* `de.drremote.trotecsl400.audio`

```bash
config:edit de.drremote.trotecsl400.audio
config:property-set baseDir ${karaf.data}/sl400
config:property-set clipsDirName clips
config:property-set sampleRate 16000
config:property-set channels 1
config:property-set sampleSizeBits 16
config:property-set bufferSeconds 30
config:property-set preRollMs 10000
config:property-set postRollMs 20000
config:property-set preferredMixerName USB
config:property-set autoStart true
config:update
```

---

## Matrix Commands

The command processor listens for `m.room.message` text messages and reacts to commands starting with `!sl400`.

Examples:

```text
!sl400 help
!sl400 status
!sl400 config
!sl400 set enable true
!sl400 set threshold 70
!sl400 set hysteresis 2
!sl400 set metric laeq5
!sl400 set mode crossing
!sl400 set interval 60000
!sl400 set dailyreport true
!sl400 set reporttime 09:00
!sl400 set reportroom !reports:matrix.example.org
!sl400 set reportjson true
!sl400 set reportgraph true
!sl400 set alerthint true
!sl400 incidents today
!sl400 incidents yesterday
!sl400 incidents since 30m
!sl400 incidents between 2026-03-27T18:00 2026-03-27T23:00
!sl400 summary today
!sl400 summary since 6h
!sl400 json yesterday
!sl400 graph since 24h
!sl400 report now
!sl400 clip last
!sl400 clip incident 1743360000000
!sl400 clips since 2h
!sl400 audio status
!sl400 audio start
!sl400 audio stop
```

---

## Alert Behavior

Alert generation is based on `AlertConfig`:

* `enabled` turns alerting on or off
* `thresholdDb` defines the trigger threshold
* `hysteresisDb` defines the reset distance below threshold
* `metricMode` chooses which metric is evaluated:

  * `LIVE`
  * `LAEQ_1_MIN`
  * `LAEQ_5_MIN`
  * `LAEQ_15_MIN`
  * `MAX_1_MIN`
* `sendMode` controls repetition:

  * `CROSSING_ONLY`
  * `PERIODIC_WHILE_ABOVE`
* `minSendIntervalMs` controls periodic resend delay

For rolling LAeq modes, alerts are only evaluated when enough window coverage exists.

---

## Stored Incident Data

Each incident can contain:

* incident id
* timestamp
* room id
* metric mode
* metric value
* threshold
* LAeq metrics
* max 1 min
* time above threshold
* local clip path
* uploaded Matrix MXC URL
* audio hint

Storage file format:

* JSONL
* default file: `${karaf.data}/sl400/incidents.jsonl`

---

## Audio Clips and Hints

When audio capture is enabled, the runtime can:

* keep a rolling PCM ring buffer
* extract pre-roll and post-roll audio around an incident
* write a WAV file
* analyze the clip
* store a simple audio hint
* upload the clip to Matrix
* remember the resulting MXC URL for reuse

Heuristic hints include:

* low signal / uncertain
* wind / rumble
* bass-heavy music
* voices / crowd
* broad noise
* mechanical / hum
* mixed / uncertain

---

## Reports

The runtime can produce:

### Summary text

Human-readable report with:

* number of incidents
* highest incident
* highest LAeq 5 min
* average incident
* time above threshold
* saved clips
* top hints
* top incidents

### JSON export

Pretty-printed incident export file.

### Graph export

PNG graph with:

* metric values over time
* LAeq 5 min series
* threshold line
* hysteresis line

### Daily report

The daily report service sends a report for **yesterday** at the configured hour/minute.

---

## Java and OSGi Notes

* Java target: **Java 21**
* Packaging:

  * regular modules use `jar`
  * features module uses `feature`
* OSGi metadata is generated with **bnd-maven-plugin**
* Declarative Services components are used throughout runtime, serial, storage, and Matrix sender bundles

---

## Typical Startup Sequence

1. Install the `sl400` feature
2. Configure serial, storage, Matrix, alerting, and audio
3. Start with Matrix disabled until serial input works
4. Verify that samples arrive from the SL400
5. Enable Matrix output
6. Test commands in the configured command room
7. Trigger a threshold event and verify:

   * incident written
   * Matrix alert sent
   * clip created
   * hint stored
   * optional report output

---

## Project Status

Current artifact version in the provided modules:

```text
0.1.0-SNAPSHOT
```

Main Karaf feature:

```text
sl400
```

Main feature repository artifact:

```text
mvn:de.drremote.trotecsl400/sl400-features/0.1.0-SNAPSHOT/xml/features
```

---

## License

Add your preferred license here, for example:

```text
MIT
```

or

```text
Apache-2.0
```

---

## Future Ideas

* better signal classification
* database-backed incident repository
* web UI / REST endpoint
* richer Matrix event schema
* configurable graph themes
* retention policy job for incidents and clips
* tests for decoder and metric engine
* packaged KAR artifact for simpler deployment
