# Deploying Spotter (server + Cloudflare Tunnel on boot)

systemd units that bring the Spotter API and a Cloudflare Tunnel up together at
boot, so your public `https://spotter.<yourdomain>` hostname is always live.

```
[phone / 5G] --HTTPS--> [Cloudflare edge] --tunnel--> [cloudflared] --HTTP--> [Spotter API @127.0.0.1:8000] --> [Postgres]
```

The API binds to localhost only; `cloudflared` is the sole public entrypoint.

## Files
| File | Purpose |
|------|---------|
| `spotter.service` | Runs `server/run.sh` (migrations + uvicorn). After Postgres. |
| `cloudflared.service` | Runs the named tunnel. Ordered after `spotter.service`. |
| `cloudflared.config.example.yml` | Template for `~/.cloudflared/config.yml`. |
| `install.sh` | Renders the unit templates, installs them, enables both. |

## One-time setup

1. **Configure the server.** In `server/`, `cp .env.example .env` and set at least
   `SECRET_KEY`, plus the public-deploy toggles: `REGISTRATION_INVITE_CODE`,
   `TRUST_PROXY=true`, `HSTS_ENABLED=true`, `DOCS_ENABLED=false`.

2. **Build the venv once** (boot-time install would be slow):
   ```bash
   cd server && ./run.sh        # Ctrl-C once it says "Application startup complete"
   ```

3. **Set up the Cloudflare Tunnel** (needs a domain on Cloudflare):
   ```bash
   cloudflared tunnel login
   cloudflared tunnel create spotter
   cloudflared tunnel route dns spotter spotter.example.com
   cp deploy/cloudflared.config.example.yml ~/.cloudflared/config.yml
   # edit it: set the tunnel UUID/credentials-file and your hostname
   ```

4. **Install + enable the services:**
   ```bash
   sudo ./deploy/install.sh
   sudo systemctl start spotter.service cloudflared.service
   ```

That's it — both are now `enabled`, so they start on every boot. The tunnel waits
for the API, and the API waits for Postgres.

## Operating
```bash
systemctl status spotter.service cloudflared.service
journalctl -u spotter.service -f          # API logs
journalctl -u cloudflared.service -f      # tunnel logs
sudo systemctl restart spotter.service    # after pulling new code
```

## Notes
- `install.sh` runs the services as the user who invoked `sudo` (override with
  `RUN_USER=...`). Adjust `HOST`/`PORT` in `spotter.service` if needed.
- Postgres must run as a systemd service named `postgresql.service` (the default on
  Debian/Ubuntu). On other distros, edit the `After=`/`Requires=` line.
- The app finds the database via `DATABASE_URL` in `server/.env`.
