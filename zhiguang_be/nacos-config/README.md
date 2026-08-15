# Zhiguang Nacos Runtime Configuration

This directory contains the first Nacos configuration package for Zhiguang.
It is deliberately limited to runtime RAG behavior. It does not contain any
real password, API key, database address, Docker setting, or OIDC secret.

## Target configuration

| Item | Value |
| --- | --- |
| Namespace | `public` (temporary first-stage choice) |
| Group | `ZHIGUANG_GROUP` |
| Data ID | `zhiguang-runtime.yaml` |
| Type | YAML |
| Conflict policy | Abort import |

`public` is used only because the current Nacos server is empty. When the
configuration becomes stable, create a separate `zhiguang-prod` namespace and
import the same package there.

## What this package owns

- RAG node model routing and model names.
- Retrieval, graph, rerank, answer, and observation switches or thresholds.
- Existing counter rebuild thresholds and hot-key thresholds.

## What must stay in server runtime .env

- Database, Redis, Kafka, Elasticsearch, Neo4j, and MinIO connection details.
- Server ports, Docker network settings, and Nacos server connection settings.
- JWT private keys, OAuth/OIDC client secrets, and all real LLM or rerank API keys.

The YAML uses environment variable placeholders for API keys on purpose. The
current Spring AI client is created at application startup, so changing a base
URL or API key needs a later code upgrade that rebuilds that client safely.
Do not assume that putting a new key in Nacos will hot-swap an already-created
Spring AI client.

## Import steps after the application has Nacos support

1. Run `build-nacos-import.ps1` from this directory. Do not create the ZIP by
   hand: Nacos 3.x needs both its root metadata file and the content file.
   Do not add an extra outer folder to the ZIP.

   The ZIP must contain this exact structure:

   ```text
   .metadata.yml
   ZHIGUANG_GROUP/zhiguang-runtime.yaml
   ^ Group          ^ Data ID
   ```

   `.metadata.yml` declares the configuration's `dataId`, `group`, and `type`.
   The second file carries its YAML content. Nacos rejects the package as empty
   when the root metadata file is missing. The entry separator must be `/`, not
   Windows `\\`; use the supplied script so the ZIP stays portable.
2. Open Nacos Configuration Management -> Configuration List -> Import.
3. Keep namespace as `public` and choose `Abort import` as the conflict policy.
4. Upload `dist/zhiguang-nacos-import.zip`.
5. Confirm that one configuration appears: `zhiguang-runtime.yaml` in
   `ZHIGUANG_GROUP`.

The import only saves the configuration in Nacos. It becomes active only after
the backend is upgraded to load this Data ID with `refreshEnabled=true`.

## Automated deployment

After the first manual import, later updates do not need another ZIP upload.
The GitHub Actions deployment publishes
`nacos-import/ZHIGUANG_GROUP/zhiguang-runtime.yaml` to the same Data ID before
restarting the backend.

The repository must contain only non-sensitive runtime settings and environment
variable placeholders. Configure these one-time GitHub values instead:

| GitHub setting | Where | Purpose |
| --- | --- | --- |
| `NACOS_SERVER_ADDR` | Actions Variable (optional) | Nacos address; defaults to `100.83.242.114:8848` |
| `NACOS_USERNAME` | Actions Secret | Nacos login username |
| `NACOS_PASSWORD` | Actions Secret | Nacos login password |

The deployment then follows one path:

```text
edit code or zhiguang-runtime.yaml
  -> git push
  -> Action publishes this Data ID to Nacos
  -> Action writes bootstrap values to the internal runtime .env
  -> backend starts and subscribes to the same Data ID
```

The runtime `.env` is still required, but Action maintains its Nacos bootstrap
keys automatically. It is not a second hand-maintained configuration source.

## Rollback

- Before a later edit, use Nacos Configuration History to inspect and roll back
  the previous version.
- Keep each reviewed ZIP as a release artifact. Import with `Abort import`, not
  overwrite, unless the exact changed Data ID has been reviewed.
