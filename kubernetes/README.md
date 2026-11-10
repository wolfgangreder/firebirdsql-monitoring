# Kubernetes Deployment

## Plain kubectl

```bash
# 1. fill in placeholders in secret.yaml and configmap.yaml
# 2. apply
kubectl apply -f kubernetes/secret.yaml
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/deployment.yaml
```

Metrics endpoint: `http://<service>:8080/q/metrics`
Health: `http://<service>:8080/q/health/live`, `/q/health/ready`, `/q/health/started`

### Health probes and database outages

Probes never touch the database. A Firebird outage neither restarts nor unreadies the pod;
it is reported by the `firebird_up` metric (`1` = reachable, `0` = down) and
`firebird_last_success_timestamp_seconds`. Alert on it, e.g. `firebird_up == 0`.
`timeoutSeconds` is set explicitly on all probes because the Kubernetes default (1s) is too
tight for a JVM under a CPU limit.

### secret.yaml

Encode password before inserting:

```bash
echo -n "yourpassword" | base64
```

Paste result into `secret.yaml` → `FIREBIRD_PASSWORD`.

### configmap.yaml

Disable sections you don't need by setting the flag to `"false"`:

| Flag | Default | Collects |
|---|---|---|
| `FIREBIRD_MONITORING_CONNECTIONS_ENABLED` | `true` | active connections |
| `FIREBIRD_MONITORING_TRANSACTIONS_ENABLED` | `true` | active transactions |
| `FIREBIRD_MONITORING_STATEMENTS_ENABLED` | `true` | active statements |
| `FIREBIRD_MONITORING_IO_ENABLED` | `true` | page I/O stats |
| `FIREBIRD_MONITORING_MEMORY_ENABLED` | `true` | memory usage |
| `FIREBIRD_STATISTICS_ENABLED` | `true` | full DB statistics (expensive) |
| `FIREBIRD_STATISTICS_INTERVAL` | `1h` | statistics collection interval |
| `FIREBIRD_SO_TIMEOUT_MS` | `10000` | JDBC socket read timeout (ms) — prevents threads hanging on dead connections |
| `FIREBIRD_CONNECT_TIMEOUT_S` | `5` | JDBC connect timeout (s) |
| `FIREBIRD_STATISTICS_SO_TIMEOUT` | `10m` | statistics service socket timeout |
| `FIREBIRD_STATISTICS_CONNECT_TIMEOUT` | `5s` | statistics service connect timeout |
| `FIREBIRD_WORKER_THREADS` | `20` | max blocking worker threads |

---

## Helm

```bash
# copy and edit the example values
cp kubernetes/helm-values-example.yaml my-values.yaml
# edit my-values.yaml — fill in host, jdbcUrl, user

# install
helm upgrade --install firebirdsql-monitoring helm/firebirdsql-monitoring \
  -f my-values.yaml \
  --set firebird.password="yourpassword" \
  --namespace monitoring --create-namespace

# uninstall
helm uninstall firebirdsql-monitoring --namespace monitoring
```

All settings from the tables above are Helm values: `firebird.monitoringInterval`,
`firebird.sections.{connections,transactions,statements,io,memory}`,
`firebird.statistics.{enabled,interval,soTimeout,connectTimeout}`, `firebird.soTimeoutMs`,
`firebird.connectTimeoutSeconds`, `firebird.workerThreads` (see `helm/firebirdsql-monitoring/values.yaml`).
Example: `--set firebird.sections.memory=false`.

Pass `--set firebird.password=...` on the command line to avoid storing the password in a file.

### Available values

| Key | Default | Description |
|---|---|---|
| `image.tag` | `0.3.2-SNAPSHOT` | Container image tag |
| `firebird.jdbcUrl` | see values.yaml | Jaybird JDBC URL |
| `firebird.user` | `sysdba` | DB user |
| `firebird.password` | `CHANGEME` | DB password — always override |
| `firebird.monitoringInterval` | `30s` | MON$ scrape interval |
| `ingress.enabled` | `false` | Create Ingress resource |
| `ingress.className` | `""` | IngressClass (e.g. `nginx`, `traefik`) |
| `prometheus.scrape` | `true` | Add `prometheus.io/scrape` pod annotation |

Full list: [`helm/firebirdsql-monitoring/values.yaml`](../helm/firebirdsql-monitoring/values.yaml)
