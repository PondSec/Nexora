# Cloudflare DNS setup for Minecraft

Minecraft Java uses its own TCP protocol, not HTTP or HTTPS. The Nginx stream proxy forwards raw TCP on port 25565. Port 443 remains in use by the existing HTTPS websites and UDP game forwarding. Cloudflare's regular orange-cloud web proxy does not proxy arbitrary Minecraft TCP traffic. Minecraft on Cloudflare Spectrum is a separate product/plan; it is not enabled here.

The Cloudflare **Name** field must be `_minecraft._tcp.nexora` so the complete SRV record becomes `_minecraft._tcp.nexora.pondsec.com`; setting only `_minecraft` publishes the wrong record name. Use a separate DNS-only hostname for the game connection so the existing HTTPS hostname can stay behind Cloudflare's proxy.

Configure these records in the `pondsec.com` zone:

1. **A** record `mc` → `80.153.171.185`, TTL Auto, **DNS only** (gray cloud). Do not add an AAAA record unless the origin gains a public IPv6 route and firewall rule.
2. Keep the existing `nexora` web A/AAAA record **proxied** (orange cloud) if you want its HTTPS traffic to continue through Cloudflare.
3. **SRV** record Name `_minecraft._tcp.nexora`, priority `0`, weight `5`, port `25565`, target `mc.pondsec.com`, TTL Auto.

The SRV directs Minecraft Java clients that enter `nexora.pondsec.com` to `mc.pondsec.com:25565`, so no port is typed. Cloudflare's regular orange-cloud web proxy does not proxy arbitrary Minecraft TCP traffic; Minecraft connects directly to the DNS-only `mc` record and bypasses Cloudflare's HTTP WAF. The Minecraft whitelist stays enforced.

Verify after saving:

```sh
dig +short A mc.pondsec.com
dig +short SRV _minecraft._tcp.nexora.pondsec.com
```

Expected A response: `80.153.171.185`. Expected SRV target/port: `mc.pondsec.com.` / `25565`.
