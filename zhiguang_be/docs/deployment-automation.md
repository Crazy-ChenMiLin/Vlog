# Zhiguang Deployment Automation

The public frontend is served at `https://web.chenmilin.xyz` on the Korea
gateway `146.56.102.26`. The backend, middleware, and self-hosted GitHub runner
remain on the internal server `100.83.242.114`.

## Gateway and backend connection

1Panel OpenResty serves the frontend from
`/opt/1panel/www/sites/zhiguang-web/index`. Its site configuration is
`/opt/1panel/www/conf.d/zhiguang-web.conf`.

The internal user's `zhiguang-kr-tunnel.service` maintains an outbound SSH
connection to the gateway on port `20131`. Two listeners bind only to the
gateway's loopback interface:

- `127.0.0.1:18080` forwards to the internal backend on port `18080`.
- `127.0.0.1:19000` forwards to the internal MinIO S3 API on port `9000`.

The tunnel uses a dedicated account and restricted key. The internal user's
linger setting keeps it running after logout and across boots. The old public
gateway at `47.108.66.230` is no longer part of this connection.

## Actions configuration

Secrets:

- `CLOUD_HOST`: `146.56.102.26`.
- `CLOUD_USER`: the gateway SSH deployment user.
- `CLOUD_SSH_KEY`: its private SSH key, stored only in GitHub Secrets.
- Existing backend credentials remain required by the full deployment workflow.

Variables:

- `CLOUD_PORT`: `20131`.
- `CLOUD_SITE_DOMAIN`: `web.chenmilin.xyz`.
- `MINIO_PUBLIC_DOMAIN` and `OSS_PRESIGN_ENDPOINT`: `https://web.chenmilin.xyz`.
- `PUBLIC_GITHUB_REDIRECT_URI`: `https://web.chenmilin.xyz/callback` (forwarded
  to the backend as `GITHUB_REDIRECT_URI`; GitHub reserves the `GITHUB_` prefix
  for its own variable names).
- `CAMPUS_REDIRECT_URI`: `https://web.chenmilin.xyz/callback/campus`.

The OAuth providers must separately allow these callback URLs. Updating an
application variable does not change the provider's registered callback.
Provider-side migration was deferred by the user on 2026-10-25.

## Full deployment

Pushing to `main` triggers `Deploy Zhiguang`. It injects the benchmark
credential, publishes Nacos settings, deploys the internal backend, builds the
frontend with Node 20, and uploads it to Korea. Scripts live under
`zhiguang_be/scripts/deploy/`.

The frontend script supports `CLOUD_PORT`, uploads through a staging directory,
reloads the active OpenResty container, and checks the named HTTPS site and a
JSON Feed response. A request to `http://127.0.0.1/` would select an unrelated
virtual host on this gateway and is not a suitable deployment check.

## Frontend-only deployment

Run `Deploy frontend only` from GitHub Actions when publishing just the
frontend. It checks out the selected revision, builds it, uploads to the same
Korea site, and performs the same checks, without deploying the backend or
publishing Nacos configuration. Both workflows share the deployment concurrency
group to prevent simultaneous uploads.

For domain migrations, update both the current backend runtime environment and
the Actions Variables. Otherwise, a later full deployment can restore stale
public file or OAuth callback addresses. Keep the backend's private MinIO
endpoint unchanged, and preserve the public Host when forwarding signed S3
requests.
