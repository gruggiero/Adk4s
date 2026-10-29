# GraalVM native-image configuration for probatio-cli.
#
# This directory holds native-image configuration files (reflection,
# resource, and dynamic proxy configs) IF hand-maintained config is
# needed. The V1 spike (Phase 0) confirmed that probatio's dependency
# set (uPickle + os-lib + mainargs) builds WITHOUT hand-maintained
# reflection config — GraalVM's automatic analysis handles all three.
#
# scalameta (concept-scanner) is spike-gated separately (R-N5). If the
# scalameta spike fails, the concept scanner stays on the JAR launcher
# and no native concept-scanner binary is produced.
#
# spec: native-packaging — Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands
# spec: native-packaging — Requirement: scalameta SHALL be spike-verified under native image before its port is scheduled
#
# Build command:
#   sbt probatioCli/nativeImage
#
# The native-image is built with --no-fallback (V1 spike finding:
# scala.Enumeration triggers reflection warnings that produce a fallback
# image without --no-fallback). This is a build flag, not a
# hand-maintained reflection config file.
