#!/bin/sh
set -eu
if [ "$(id -u)" != 0 ]; then
  echo 'Avvia con sudo sh deploy/install.sh'
  exit 1
fi
cd "$(dirname "$0")/.."
install -d -m 755 /opt/pi-drive/server /usr/local/lib/pi-drive
install -o root -g root -m 644 server/app.py server/disks.py /opt/pi-drive/server/
install -o root -g root -m 644 server/mount_drive.py server/disks.py /usr/local/lib/pi-drive/
install -o root -g root -m 644 deploy/pi-drive.service /etc/systemd/system/pi-drive.service
if [ ! -f /etc/pi-drive.env ]; then
  umask 077
  /usr/bin/python3 -c 'import secrets; print("PIDRIVE_TOKEN=" + secrets.token_urlsafe(24)); print("PIDRIVE_STATE=/home/dvlce/.local/share/pi-drive")' > /etc/pi-drive.env
fi
chmod 600 /etc/pi-drive.env
printf '%s\n' 'dvlce ALL=(root) NOPASSWD: /usr/bin/python3 /usr/local/lib/pi-drive/mount_drive.py *' > /etc/sudoers.d/pi-drive
chmod 440 /etc/sudoers.d/pi-drive
visudo -cf /etc/sudoers.d/pi-drive
systemctl daemon-reload
systemctl enable --now pi-drive
echo 'Pi Drive avviato sulla porta 8090. Il codice privato è in /etc/pi-drive.env.'
