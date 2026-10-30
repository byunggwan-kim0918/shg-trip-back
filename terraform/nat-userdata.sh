#!/bin/bash
dnf install -y iptables-services
systemctl enable iptables
systemctl start iptables

cat <<'EOF' > /etc/sysctl.d/custom-ip-forwarding.conf
net.ipv4.ip_forward=1
EOF
sysctl -p /etc/sysctl.d/custom-ip-forwarding.conf

IFACE=$(ip route show default | awk '{print $5; exit}')
iptables -t nat -A POSTROUTING -o "$IFACE" -j MASQUERADE
iptables -F FORWARD
service iptables save