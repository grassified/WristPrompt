# Security

Never post API keys, tokens, authorization codes, callback URLs, cookies, PKCE
material, signing keys or credential-bearing reproduction data in public issues.
Redact logs and use synthetic credentials. If credentials were exposed, rotate or
revoke them through the provider; deleting a Git file does not remove its history.

GitHub Private Vulnerability Reporting is enabled. For authentication, storage,
credential-transfer or other security vulnerabilities, use
[Report a vulnerability](https://github.com/grassified/WristPrompt/security/advisories/new)
to submit a confidential report to the repository maintainers. You can also open
the repository's **Security → Advisories → Report a vulnerability** page.
Do not disclose vulnerability details in public issues. No private reporting email
is designated. If the GitHub reporting form is unavailable, request a confidential
channel without posting exploit details or credentials publicly.

Review [PROJECT_SPEC.md](PROJECT_SPEC.md) for storage/protocol requirements and
[PRIVACY.md](PRIVACY.md) for data flow. v0.1.0 is an early source release with no promised security-response SLA.
