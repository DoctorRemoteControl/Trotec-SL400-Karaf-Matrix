## Karaf Configuration Example

This bundle uses these ConfigAdmin PIDs:

- `de.drremote.trotecsl400.alert`
- `de.drremote.trotecsl400.matrix`
- `de.drremote.trotecsl400.matrix.status`
- `de.drremote.trotecsl400.audio`

---

## Alert Configuration

PID:

- `de.drremote.trotecsl400.alert`

```sh
config:edit de.drremote.trotecsl400.alert
config:property-set enabled true
config:property-set thresholdDb 70.0
config:property-set hysteresisDb 2.0
config:property-set minSendIntervalMs 60000
config:property-set sendMode CROSSING_ONLY
config:property-set metricMode LAEQ_5_MIN
config:property-set allowedSendersCsv @operator:matrix.example.org,@second-operator:matrix.example.org
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

## Matrix Configuration

PID:

* `de.drremote.trotecsl400.matrix`

```sh
config:edit de.drremote.trotecsl400.matrix
config:property-set homeserverBaseUrl https://matrix.example.org
config:property-set accessToken syt_xxxxxxxxxxxxxxxxx
config:property-set roomId !alerts:matrix.example.org
config:property-set enabled true
config:update
```

---

## Matrix Status Publishing Configuration

PID:

* `de.drremote.trotecsl400.matrix.status`

```sh
config:edit de.drremote.trotecsl400.matrix.status
config:property-set enabled true
config:property-set publishIntervalMs 60000
config:property-set maxSilenceMs 300000
config:property-set offlineThresholdMs 15000
config:property-set onlyOnChange true
config:property-set statusDbStep 1.0
config:property-set statusRoomId
config:update
```

---

## Audio Configuration

PID:

* `de.drremote.trotecsl400.audio`

```sh
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
