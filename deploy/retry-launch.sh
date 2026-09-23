#!/usr/bin/env bash
# Retry an Always Free ARM instance launch until Oracle has capacity.
#
# "Out of capacity for shape VM.Standard.A1.Flex" is not a configuration error -- free ARM
# capacity is heavily contested and frees up at random. Rather than clicking the console for
# hours, loop on it. Most people land an instance within a few hours.
#
# Setup (once):
#   brew install oci-cli && oci setup config      # needs your tenancy + user OCID
#
# Find the OCIDs it needs:
#   oci iam compartment list --all --query "data[].{name:name,id:id}" --output table
#   oci network subnet list -c <compartment-ocid> --query "data[].{name:\"display-name\",id:id}" --output table
#   oci compute image list -c <compartment-ocid> --operating-system "Canonical Ubuntu" \
#       --operating-system-version "22.04" --shape VM.Standard.A1.Flex \
#       --query "data[0].{name:\"display-name\",id:id}" --output table
#
# Then:
#   export COMPARTMENT_OCID=ocid1.compartment.oc1..xxx
#   export SUBNET_OCID=ocid1.subnet.oc1..xxx
#   export IMAGE_OCID=ocid1.image.oc1..xxx
#   export SSH_PUBLIC_KEY=~/.ssh/id_ed25519.pub
#   ./deploy/retry-launch.sh
set -euo pipefail

: "${COMPARTMENT_OCID:?set COMPARTMENT_OCID}"
: "${SUBNET_OCID:?set SUBNET_OCID}"
: "${IMAGE_OCID:?set IMAGE_OCID}"
SSH_PUBLIC_KEY="${SSH_PUBLIC_KEY:-$HOME/.ssh/id_ed25519.pub}"

# The stack measures ~2.5 GB, so 6 GB is ample and lands far more often than 12 GB.
OCPUS="${OCPUS:-1}"
MEMORY_GB="${MEMORY_GB:-6}"
DISPLAY_NAME="${DISPLAY_NAME:-techies}"
SLEEP_SECONDS="${SLEEP_SECONDS:-60}"
CLOUD_INIT="${CLOUD_INIT:-$(dirname "$0")/cloud-init.yaml}"

[ -f "$SSH_PUBLIC_KEY" ] || { echo "no public key at $SSH_PUBLIC_KEY" >&2; exit 1; }
[ -f "$CLOUD_INIT" ]     || { echo "no cloud-init at $CLOUD_INIT" >&2; exit 1; }

# Cycle every availability domain: capacity is per-AD, so one may free up while others stay
# full. Built with a read loop rather than mapfile, which needs bash 4 and macOS ships 3.2.
ADS=()
while IFS= read -r ad; do
  [ -n "$ad" ] && ADS+=("$ad")
done < <(oci iam availability-domain list -c "$COMPARTMENT_OCID" \
  --query 'data[].name' --raw-output 2>/dev/null | tr -d '[]", ' | grep -v '^$')

if [ "${#ADS[@]}" -eq 0 ]; then
  echo "could not list availability domains -- check your OCI CLI config" >&2
  exit 1
fi

echo "Retrying ${OCPUS} OCPU / ${MEMORY_GB} GB across ${#ADS[@]} availability domain(s)."
echo "Ctrl-C to stop. Every ${SLEEP_SECONDS}s per attempt."
echo

attempt=0
while true; do
  for ad in "${ADS[@]}"; do
    attempt=$((attempt + 1))
    printf '[%s] attempt %-4d %s ... ' "$(date +%H:%M:%S)" "$attempt" "$ad"

    # No fault domain is specified on purpose: pinning one only narrows the capacity pool.
    if out=$(oci compute instance launch \
          --compartment-id "$COMPARTMENT_OCID" \
          --availability-domain "$ad" \
          --shape VM.Standard.A1.Flex \
          --shape-config "{\"ocpus\":${OCPUS},\"memoryInGBs\":${MEMORY_GB}}" \
          --image-id "$IMAGE_OCID" \
          --subnet-id "$SUBNET_OCID" \
          --assign-public-ip true \
          --display-name "$DISPLAY_NAME" \
          --ssh-authorized-keys-file "$SSH_PUBLIC_KEY" \
          --user-data-file "$CLOUD_INIT" \
          --wait-for-state RUNNING 2>&1); then
      echo "LAUNCHED"
      echo
      echo "$out" | grep -o '"public-ip": "[^"]*"' || true
      echo
      echo "Next: add the VCN ingress rule for port 80, then"
      echo "  scp ~/.ssh/id_ed25519_gh ubuntu@<ip>:~/.ssh/"
      echo "  ssh ubuntu@<ip> 'sudo /opt/techies-deploy.sh'"
      exit 0
    fi

    if grep -qi "out of capacity\|OutOfCapacity\|LimitExceeded" <<<"$out"; then
      echo "no capacity"
    else
      # A real error -- bad OCID, quota, permissions -- will never fix itself by retrying.
      echo "FAILED"
      echo "$out" | tail -5 >&2
      exit 1
    fi
  done
  sleep "$SLEEP_SECONDS"
done
