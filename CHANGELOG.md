Change Log
==========

All notable changes to this project will be documented in this file, which
follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
This project adheres to [Semantic Versioning](http://semver.org/).


## [Unreleased]

### Changed
- Updated dependency versions.
- Updated GraalVM build process to run more in Clojure tooling and less in
  shell scripts.

### Added
- The server will log the running version on startup.
- New environment variable `DYNR53_TARGETS_FILE` can be used to provide a path
  to a file which will be reread to set DNS targets. Each line should contain a
  hostname, a tab separator, and an IP address.

### Fixed
- Resolved reflection issue which threw an error in the native-image when
  stopping the worker thread.
- The logs should be less spammy now.


## [0.2.32] - 2023-09-04

- Added an option to configure a state directory, where targets will be
  persisted between runs.
- Embedded version and build information for use at runtime.
- Added a basic command structure to the CLI, supporting `server` (the default),
  `help`, and `version`.


## [0.1.22] - 2023-08-31

Initial release.


[Unreleased]: https://github.com/greglook/inadynr53/compare/0.2.32...HEAD
[0.2.32]: https://github.com/greglook/inadynr53/compare/0.1.22...0.2.32
[0.1.22]: https://github.com/greglook/inadynr53/tag/0.1.22
