# Owner Skill Discovery and Installation

Dark Lord will let an authenticated owner discover, download, validate, dry-run, install, list, and remove declarative skills from owner-supplied HTTPS URLs. Skill packages remain private to the app, are bounded and validated before activation, and are audited with rollback-safe replacement. Unknown principals cannot invoke skill-management operations.

Downloads use a bounded HTTPS transport with redirects rejected, optional SHA-256 verification, and no executable payloads. The existing `SkillPackageValidator`, `SkillUpdateService`, and `SkillInstaller` remain the validation and activation boundary. Installed skill IDs are projected into the owner model context only after successful installation.
