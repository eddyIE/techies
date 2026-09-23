# Deploying to an Oracle Cloud Always Free VM

Runs the same `docker compose` stack you run locally, on a free ARM VM. Nothing about the
architecture changes: private networking stays private, there are no cold starts, and the
database does not expire.

Chosen over Render because Render's free tier forbids private network traffic, which would
force every service to be publicly reachable and break the gateway's identity model. See
"Why not Render" at the end.

---

## Before you start: read this

Once this has a public IP, three things in the current design become real risks. They were
acceptable for a local university project and are **not** acceptable on the open internet.

1. **Password reset has no ownership proof.** `POST /auth/check-email` tells anyone whether
   an account exists, and `POST /auth/reset-password` then changes its password given only
   the email. **Anyone who knows an email address can take over that account.** This was a
   deliberate choice for speed (`docs/SPEC-identity.md`), and it is fine on localhost. In
   public, either accept it and **use only fake emails and throwaway passwords**, or spend
   the ~15 lines to add the short-lived token described in `docs/EXTENSIONS.md`.
2. **No rate limiting anywhere.** Login, registration and reset can be hit as fast as the
   network allows.
3. **Seed data is public.** That is fine, but do not add anything real.

Treat the deployment as a demo, not a service. Do not register with a real email and password
you use anywhere else.

---

## 0. Choose your home region — this is permanent

Do this before anything else. OCI asks for a **home region** at signup, and you should treat
it as unchangeable: Always Free resources, including the ARM instance this guide depends on,
only exist in your home region. Picking wrong means opening a new account.

From Vietnam, every sensible option is in the Singapore cluster:

| Region | Identifier | Notes |
|---|---|---|
| **Singapore** | `ap-singapore-1` | Closest and lowest latency, but the most fought-over for free ARM |
| **Singapore West** | `ap-singapore-2` | Same city, newer — better odds of free ARM capacity |
| **Indonesia (Batam)** | `ap-batam-1` | ~20 km from Singapore, newer, least contended |
| **Malaysia (Kulai)** | `ap-kulai-2` | ~60 km from Singapore, newer |
| Japan (Tokyo / Osaka) | `ap-tokyo-1` / `ap-osaka-1` | ~4,000 km; adds roughly 40 ms |
| South Korea (Chuncheon) | `ap-chuncheon-1` | **Avoid — no Ampere A1 in any availability domain** |

**Recommendation: `ap-singapore-1`,** unless you hit repeated "Out of host capacity", in
which case `ap-batam-1` or `ap-singapore-2` are within a few milliseconds of it and much
easier to get an instance in.

The latency difference barely matters here — Singapore is roughly 30 ms from Vietnam and
Tokyo roughly 70 ms, and nobody grading a REST API will notice. **Capacity is the real
constraint**, because a region you cannot launch a free instance in is worth nothing.

## 1. Create the VM, with cloud-init

In the Oracle Cloud console: **Compute → Instances → Create instance**.

| Setting | Value |
|---|---|
| Shape | **Ampere A1 Compute** (`VM.Standard.A1.Flex`) |
| OCPUs / memory | **2 OCPU, 12 GB** — see the allowance note below |
| Image | Ubuntu 22.04 or 24.04 (aarch64) |
| Boot volume | 50 GB (47 GB is the minimum per instance) |
| SSH key | Upload your public key; you cannot add it later without recovery |

> **The Always Free ARM allowance is 1,500 OCPU-hours and 9,000 GB-hours per month.** Run
> continuously, that works out at **2 OCPU and 12 GB** — not the 4 OCPU / 24 GB often quoted
> in older write-ups. You can split it (two 1-OCPU instances) but one 2 OCPU / 12 GB box is
> the right shape here. 12 GB is still ample: six JVMs use roughly 2.5 GB.

> **If you get "Out of host capacity"**, that is the usual Always Free ARM shortage, not a
> mistake on your part. Try a different availability domain, or retry over a few hours. A
> 1 OCPU / 6 GB shape also runs this stack.

Note the **public IP** when it finishes.

### Paste the cloud-init script

Before clicking Create, open **Show advanced options → Management → Cloud-init script** and
paste the contents of `deploy/cloud-init.yaml`.

It installs Docker, opens port 80 in the *host* firewall, pre-trusts GitHub's host key, adds
the keep-alive cron, and writes `/opt/techies-deploy.sh` — everything that needs no
credentials.

**It deliberately does not clone the repo.** `eddyIE/techies` is private, and cloud-init
user-data is readable from inside the instance via the metadata service at `169.254.169.254`,
so a key pasted there would be readable by anyone who gets shell.

Watch it finish:

```bash
ssh ubuntu@<public-ip>
tail -f /var/log/techies-prep.log
ls /opt/techies-prep.done          # appears when prep is done
```

## 2. Open port 80 in the VCN

Oracle blocks traffic in two independent places, and missing either looks identical: the site
simply never responds. Cloud-init already handled the host firewall; **this layer is console
only, so it cannot be automated.**

Networking → your VCN → Subnet → Security List → Add Ingress Rule:

| Field | Value |
|---|---|
| Source CIDR | `0.0.0.0/0` |
| IP protocol | TCP |
| Destination port | `80` |

> If the API is unreachable, this rule is the usual cause. Confirm the host side with
> `sudo iptables -L INPUT -n --line-numbers | grep 80` on the VM.

## 3. Give the VM read access, then deploy

### Preferred: a read-only deploy key

Scoped to this one repository and unable to push. Two commands on your laptop:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/techies_deploy -C "techies oracle vm" -N ""
cat ~/.ssh/techies_deploy.pub
```

Paste that public key at **github.com/eddyIE/techies → Settings → Deploy keys → Add deploy
key**. Leave *Allow write access* unchecked. Then:

```bash
scp ~/.ssh/techies_deploy ubuntu@<public-ip>:~/.ssh/
ssh ubuntu@<public-ip> 'sudo DEPLOY_KEY=/home/ubuntu/.ssh/techies_deploy /opt/techies-deploy.sh'
```

### Alternative: your existing account key

`~/.ssh/id_ed25519_gh` is the default the script expects, so this works with no extra flags:

```bash
scp ~/.ssh/id_ed25519_gh ubuntu@<public-ip>:~/.ssh/
ssh ubuntu@<public-ip> 'sudo /opt/techies-deploy.sh'
```

> **Understand what this hands over.** That key authenticates as the GitHub *account*
> `eddyIE`, not as this repository, so it can **push to every repo your account can reach**.
> A VM on the public internet is a poor place to keep it, particularly one running an API
> whose password reset takes no proof of ownership. The deploy key above takes 30 seconds and
> removes the risk entirely. If you use the account key anyway, delete it from the VM after
> the demo:
> ```bash
> ssh ubuntu@<public-ip> 'shred -u ~/.ssh/id_ed25519_gh'
> ```

### What the deploy script does

`REPO_URL` defaults to `git@github.com:eddyIE/techies.git` and `DEPLOY_KEY` to
`~/.ssh/id_ed25519_gh`; override either with an environment variable. The script clones (or
fast-forwards an existing checkout), generates `JWT_SECRET` and `POSTGRES_PASSWORD` on the
first run only, **refuses to start if the shipped placeholders are still in `.env`**, and
brings the stack up with the production overlay.

Re-run the same command to redeploy after a push. Existing secrets are kept, so app tokens
keep working.

Expect **15-25 minutes** on the first build.

## 4. Verify

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps   # 7 healthy

# Eureka registrations take ~30s after the containers report healthy.
curl -s http://localhost/api/categories | head -c 200
```

From your laptop:

```bash
curl -s http://<public-ip>/api/categories
API=http://<public-ip>/api python3 scripts/demo.py   # full walkthrough
```

Reaching Postgres safely, without exposing it:

```bash
ssh -L 5432:localhost:5432 ubuntu@<public-ip>
psql -h localhost -U techies -d techies
```

## 5. Operating it

```bash
# logs (same files as locally)
tail -f logs/order-service-error.log
grep -rh "API PROBLEM\|API FAILURE" logs/*-error.log | tail -20

# redeploy after a push
git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# full reset, wipes the database and reseeds
docker compose -f docker-compose.yml -f docker-compose.prod.yml down -v
```

Memory: six JVMs at roughly 400 MB each is about 2.5 GB of the 12 GB available.

> **Keep the instance busy enough not to be reclaimed.** Oracle may reclaim an Always Free
> instance that stays under 20% CPU **and** under 20% network **and** under 20% memory
> (A1 shapes) across a 7-day window. Six idle JVMs sit near that memory line, so an untouched
> demo box is not guaranteed to survive. Before a deadline, either hit it periodically — a
> cron'd `curl http://localhost/api/categories` every few minutes is enough — or be ready to
> redeploy. A paid instance is never reclaimed.

---

## Preemptible instances, and surviving a lost VM

### Should you use a preemptible instance? No.

Preemptible capacity is **50% cheaper than on-demand** — but your Always Free A1 instance
already costs nothing, and 50% of nothing is nothing. Preemptible only saves money once you
are paying, which at this traffic level you will not be.

What it would cost you is real: a preemptible instance can be terminated **at any time with
two minutes' notice**, which is a poor property for a box you may need to show a lecturer.
Use a normal on-demand Always Free instance.

(For completeness: `VM.Standard.A1.Flex` *is* a supported preemptible shape, and you can
choose whether the boot volume is deleted on reclamation. The economics are the problem,
not the capability.)

### Your instance can still vanish

Always Free instances are reclaimed if, across a 7-day window, they stay under **20% CPU and
20% network and 20% memory**. An untouched demo box fits that description exactly.

Two defences, both already in `deploy/cloud-init.yaml`:

1. A keep-alive cron hits `http://localhost/api/categories` every 5 minutes, which also
   doubles as a health probe.
2. The whole build is automated, so replacing the VM is one instance-create, not an
   afternoon of re-reading this guide.

### Rebuilding from nothing

Repeat steps 1 to 3: create the instance with `deploy/cloud-init.yaml` pasted in, add the VCN
ingress rule, copy the deploy key up and run `sudo /opt/techies-deploy.sh`.

That is roughly five minutes of clicking plus a 15-25 minute build, against an afternoon of
following this guide by hand. The VCN rule is the only part that cannot be automated.

### What survives a rebuild, and what does not

| | Survives? |
|---|---|
| Categories, products, stock | **Yes** — reseeded by Flyway on first boot |
| User accounts, carts, orders | **No** — the database volume goes with the VM |
| JWT secret | **No** by default, so previously issued app tokens stop working |

This is the useful consequence of seeding everything by migration: a rebuilt instance is
immediately demo-ready with the full catalogue. Only accounts created during a demo are lost,
and those take seconds to recreate.

If you would rather keep app tokens valid across a rebuild, set `JWT_SECRET_OVERRIDE` in the
cloud-init script to the same value each time. Keep it somewhere private — anyone holding it
can mint a token for any user.

To preserve real order history you would need a database backup, which is genuinely out of
scope here; `docker compose exec postgres pg_dump` on a schedule is the starting point.

---

## Appendix: manual setup, if you skipped cloud-init

Only needed if you did not paste `deploy/cloud-init.yaml` at creation.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker $USER && newgrp docker
docker --version && docker compose version
```

All three base images (`maven`, `eclipse-temurin`, `postgres`) publish `linux/arm64`, so
everything builds natively on Ampere. No emulation, no `--platform` flag.

---

## Why not Render

Render's free tier states that free web services **cannot receive private network traffic**,
and private services are not offered on the free plan. That forces every service to be
public, which means:

- `/internal/**` and `/stock/deduct` become publicly callable — anyone could move your stock.
- The gateway's `X-User-Id` injection stops being safe. Its security depends on services
  being unreachable except through the gateway (`docs/SPEC-gateway.md`). Public services mean
  a forged header impersonates any user.

The resource limits rule it out independently: **750 free instance hours per month** against
6 services × 730 h = 4,380 h needed, free Postgres **deleted after 30 days**, and a ~1 minute
cold start per service after 15 minutes idle — which the Feign timeouts (2 s connect, 5 s
read) would fail long before the service finished booting.

Paid Render is about $49/month for six services plus Postgres, and still needs Eureka
replaced with Render's internal hostnames.
