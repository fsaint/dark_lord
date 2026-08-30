# Stage 8 MCP and Skills Operations Checklist

Stage 8 automated verification covers scoped MCP discovery/calls, Streamable HTTP and OAuth lifecycle behavior, private inbound transport authentication, and declarative skill validation/install/update/rollback.

Before connecting a real service, configure an HTTPS Streamable HTTP endpoint and enroll its connection and client identity through the owner-controlled setup flow. Never paste refresh tokens into logs or ordinary configuration; production secret storage must use the Android Keystore-backed adapter.

Expected safety behavior:

- ungranted principals cannot discover or call MCP connections;
- non-Tailscale or unknown inbound clients are rejected;
- invalid, executable, oversized, or path-traversing skill archives are rejected;
- a failed skill update leaves the previously active version unchanged.

Real endpoint connectivity and Tailscale enrollment remain deployment-specific checks and are not automated against external services.
