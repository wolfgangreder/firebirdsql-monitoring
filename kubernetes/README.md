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
Health: `http://<service>:8080/q/health/live`

### secret.yaml

Encode password before inserting:

```bash
echo -n "yourpassword" | base64
```

Paste result into `secret.yaml` → `FIREBIRD_PASSWORD`.

### configmap.yaml

Set `FIREBIRD_JDBC_URL` to your Firebird JDBC URL, e.g.:

```
jdbc:firebird://<replace-with-host>:<replace-with-port>//<replace-with-db-path>
```

Disable sections you don't need by setting the flag to `"false"`:

| Flag | Default | Collects |
|---|---|---|
| `FIREBIRD_MONITORING_CONNECTIONS_ENABLED` | `true` | active connections |
| `FIREBIRD_MONITORING_TRANSACTIONS_ENABLED` | `true` | active transactions |
| `FIREBIRD_MONITORING_STATEMENTS_ENABLED` | `true` | active statements |
| `FIREBIRD_MONITORING_IO_ENABLED` | `true` | page I/O stats |
| `FIREBIRD_MONITORING_MEMORY_ENABLED` | `true` | memory usage |
| `FIREBIRD_STATISTICS_ENABLED` | `true` | full DB statistics (expensive, default 1h) |

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

Pass `--set firebird.password=...` on the command line to avoid storing the password in a file.

### Available values

| Key | Default | Description |
|---|---|---|
| `image.tag` | `1.0-SNAPSHOT` | Container image tag |
| `firebird.jdbcUrl` | see values.yaml | Jaybird JDBC URL |
| `firebird.user` | `sysdba` | DB user |
| `firebird.password` | `CHANGEME` | DB password — always override |
| `firebird.monitoringInterval` | `30s` | MON$ scrape interval |
| `ingress.enabled` | `false` | Create Ingress resource |
| `ingress.className` | `""` | IngressClass (e.g. `nginx`, `traefik`) |
| `prometheus.scrape` | `true` | Add `prometheus.io/scrape` pod annotation |

Full list: [`helm/firebirdsql-monitoring/values.yaml`](../helm/firebirdsql-monitoring/values.yaml)
