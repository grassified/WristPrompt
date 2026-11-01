# Security

Never post API keys, tokens, authorization codes, callback URLs, cookies, PKCE
material, signing keys or credential-bearing reproduction data in public issues.
Redact logs and use synthetic credentials. If credentials were exposed, rotate or
revoke them through the provider; deleting a Git file does not remove its history.

For authentication, storage or credential-transfer vulnerabilities, request a
confidential reporting channel from the repository maintainer without disclosing
exploit details or credentials publicly. No private reporting email is designated.
GitHub private vulnerability reporting was verified disabled on 2026-11-02; this
release does not claim that the service is enabled. Enabling it requires separate
repository-setting authorization.

Review [PROJECT_SPEC.md](PROJECT_SPEC.md) for storage/protocol requirements and
[PRIVACY.md](PRIVACY.md) for data flow. v0.1.0 is an early source release with no promised security-response SLA.
