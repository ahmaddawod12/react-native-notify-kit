# Security Policy

## Supported Versions

Security fixes are provided for the latest published release line of `react-native-notify-kit`.

| Version                                  | Supported                                 |
| ---------------------------------------- | ----------------------------------------- |
| Latest published release line            | :white_check_mark:                        |
| Older major release lines                | Case-by-case for critical vulnerabilities |
| `@notifee/react-native` upstream package | :x:                                       |

The original `@notifee/react-native` package is archived and is not maintained by this project. Users affected by security issues in the archived upstream package should migrate to `react-native-notify-kit`.

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues, public discussions, pull requests, or social media.

To report a vulnerability, use GitHub private vulnerability reporting:

https://github.com/marcocrupi/react-native-notify-kit/security/advisories/new

If GitHub private vulnerability reporting is not available to you, contact the maintainer at:

[marcocrupi@hotmail.it](mailto:marcocrupi@hotmail.it)

When reporting a vulnerability, please include as much detail as possible:

* A clear description of the issue
* The affected package version
* The affected platform, such as Android, iOS, Expo CNG, or the server SDK
* React Native version and relevant toolchain versions, if applicable
* Steps to reproduce or a minimal proof of concept
* The expected impact
* Whether the issue is already being exploited or publicly known

## Response Expectations

This is a single-maintainer community project with no guaranteed SLA.

I will make a best effort to acknowledge valid vulnerability reports within 7 days. Complex issues may require more time to reproduce, validate, fix, and release.

If the report is accepted, I will coordinate with the reporter on the fix, release timing, and disclosure. Security fixes may be released without full technical details until users have had a reasonable opportunity to upgrade.

If the report is declined, I will explain the reason when appropriate.

## Disclosure Policy

Please keep vulnerability details private until a fix is released or until coordinated disclosure is agreed.

Reporter credit will be given when appropriate, unless the reporter prefers to remain anonymous.

## Scope

This security policy applies to vulnerabilities in this repository and in the published `react-native-notify-kit` package.

Examples of in-scope reports include:

* Vulnerabilities in native Android or iOS notification handling
* Vulnerabilities in the React Native bridge
* Vulnerabilities in the FCM Mode client or server SDK payload handling
* Vulnerabilities in the CLI or Expo config plugin that could affect consumer projects
* Dependency vulnerabilities that are exploitable through this package

Examples of out-of-scope reports include:

* General support questions
* Non-security bugs
* Feature requests
* Vulnerabilities in consumer app code
* Vulnerabilities in the archived upstream `@notifee/react-native` package that are not present in `react-native-notify-kit`
