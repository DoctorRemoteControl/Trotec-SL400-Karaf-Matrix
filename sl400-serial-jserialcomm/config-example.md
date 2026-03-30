## Karaf Configuration Example

This bundle uses this ConfigAdmin PID:

- `de.drremote.trotecsl400.serial`

---

## Serial Configuration

PID:

- `de.drremote.trotecsl400.serial`

```sh
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
