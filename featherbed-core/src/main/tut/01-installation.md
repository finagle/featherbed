---
title: Installation
layout: default
---

# Installation

Add the following to build.sbt

```scala
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "io.github.finagle" %% "featherbed-core" % "0.2.1-SNAPSHOT",
  "io.github.finagle" %% "featherbed-circe" % "0.2.1-SNAPSHOT"
)
```
Next, read about [Basic Usage](02-basic-usage.html)
