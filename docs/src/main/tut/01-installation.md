---
title: Installation
layout: default
---

# Installation

Add the following to build.sbt

```scala
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "io.github.finagle" %"featherbed_2.11" %"0.2.1-SNAPSHOT"
)
```
Next, read about [Basic Usage](02-basic-usage.html)
