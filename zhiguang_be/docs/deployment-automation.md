# Zhiguang Deployment Automation

This project uses a split deployment:

- Frontend static files are served by the public cloud gateway.
- Backend runs on the internal middleware server.
- The public cloud gateway forwards `/api` and `/zhiguang` through Tailscale.

## Recommended GitHub Actions Model

Use a GitHub self-hosted runner on the internal server.

Reason: GitHub-hosted runners cannot directly reach the private/internal server. A self-hosted runner runs inside the same network as the backend and can build the backend without loading the lightweight public cloud server.

## Required GitHub Secrets

- `INTERNAL_SUDO_PASSWORD`: sudo password for backend Docker deployment on the internal server.
- `CLOUD_HOST`: public gateway host, usually `47.108.66.230`.
- `CLOUD_USER`: SSH user for the public gateway, usually `root`.
- `CLOUD_SSH_KEY`: private SSH key that can log in to the public gateway.
- `SMTP`: optional 163 SMTP authorization code used to update `SPRING_MAIL_PASSWORD` in the backend runtime `.env`.

## Workflow

On every push to `main`, or when you manually run `Deploy Zhiguang`:

1. GitHub self-hosted runner checks out the repository.
2. Backend deploy script syncs the checked-out repository to `/home/chenmilin/zhiguang-deploy/Vlog`.
3. Backend Docker Compose uses `/home/chenmilin/zhiguang-deploy/runtime/.env` and rebuilds `zhiguang-be`.
4. Frontend is built with Node 20.
5. Frontend `dist` is synced to `/var/www/zhiguang-fe` on the public gateway.
6. Nginx is reloaded and `/api/v1/knowposts/feed` is checked.

## Manual Setup Still Needed

Register the internal server as a GitHub self-hosted runner. The workflow currently targets any `self-hosted` runner in this repository, matching the checkout test workflow.

After that, a normal local workflow becomes:

```bash
git add .
git commit -m "your change"
git push origin main
```

Then GitHub Actions deploys the project.
