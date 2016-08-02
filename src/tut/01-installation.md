---
title: Installation
layout: default
---

# Installation

Add the following to build.sbt

```tut:book
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "io.github.finagle" %"featherbed_2.11" %"0.1.0-SNAPSHOT"
)
```
Next, read about [Basic Usage](02-basic-usage.html)
