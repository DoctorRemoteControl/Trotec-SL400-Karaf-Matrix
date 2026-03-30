## Karaf Configuration Example

This bundle uses this ConfigAdmin PID:

- `de.drremote.trotecsl400.storage`

---

## Storage Configuration

PID:

- `de.drremote.trotecsl400.storage`

```sh
config:edit de.drremote.trotecsl400.storage
config:property-set baseDir ${karaf.data}/sl400
config:property-set fileName incidents.jsonl
config:update
```
