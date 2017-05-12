name := "featherbed"

import sbtunidoc.Plugin.UnidocKeys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._

enablePlugins(TutPlugin)

lazy val buildSettings = Seq(
  organization := "io.github.finagle",
  version := "0.3.1",
  scalaVersion := "2.12.2",
  crossScalaVersions := Seq("2.11.11", "2.12.2")
)

val finagleVersion = "6.44.0"
val shapelessVersion = "2.3.2"
val catsVersion = "0.9.0"

lazy val docSettings = Seq(
  autoAPIMappings := true
)

lazy val baseSettings = docSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.twitter" %% "finagle-http" % finagleVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats" % catsVersion,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % "test",
    "org.scalatest" %% "scalatest" % "3.0.3" % "test"
  ),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  dependencyUpdatesFailBuild := false,
  dependencyUpdatesExclusions := moduleFilter("org.scala-lang")
)

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishArtifact := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  homepage := Some(url("https://finagle.github.io/featherbed/")),
  autoAPIMappings := true,
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/finagle/featherbed"),
      "scm:git:git@github.com:finagle/featherbed.git"
    )
  ),
  pomExtra :=
    <developers>
      <developer>
        <id>jeremyrsmith</id>
        <name>Jeremy Smith</name>
        <url>https://github.com/jeremyrsmith</url>
      </developer>
    </developers>
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val allSettings = publishSettings ++ baseSettings ++ buildSettings

lazy val `featherbed-core` = project
  .settings(allSettings)

lazy val `featherbed-circe` = project
  .settings(allSettings)
  .dependsOn(`featherbed-core`)

val scaladocVersionPath = settingKey[String]("Path to this version's ScalaDoc")
val scaladocLatestPath = settingKey[String]("Path to latest ScalaDoc")
val tutPath = settingKey[String]("Path to tutorials")

lazy val docs: Project = project
    .settings(
      allSettings ++ ghpages.settings ++ Seq(
        scaladocVersionPath := ("api/" + version.value),
        scaladocLatestPath := (if (isSnapshot.value) "api/latest-snapshot" else "api/latest"),
        tutPath := "doc",
        includeFilter in makeSite := (includeFilter in makeSite).value || "*.md" || "*.yml",
        addMappingsToSiteDir(tut, tutPath),
        addMappingsToSiteDir(mappings in (featherbed, ScalaUnidoc, packageDoc), scaladocLatestPath),
        addMappingsToSiteDir(mappings in (featherbed, ScalaUnidoc, packageDoc), scaladocVersionPath),
        ghpagesNoJekyll := false,
        git.remoteRepo := "git@github.com:finagle/featherbed",
        scalacOptions in Tut := (
          CrossVersion.partialVersion(scalaVersion.value) match {
            case Some((2, p)) if p >= 12 => Seq("-Yrepl-class-based")
            case _ => Nil
          }
        )
      )
    ).dependsOn(`featherbed-core`, `featherbed-circe`)


lazy val featherbed = project
  .in(file("."))
  .settings(unidocSettings ++ baseSettings ++ buildSettings ++ publishSettings)
  .aggregate(`featherbed-core`, `featherbed-circe`)
  .dependsOn(`featherbed-core`, `featherbed-circe`)
  .settings(
    initialCommands in console :=
      """
          |import com.twitter.util.{Await, Future}
          |import com.twitter.finagle.{Service, Http}
          |import com.twitter.finagle.http.{Request, Response, Method}
          |import java.net.{InetSocketAddress, URL}
          |import shapeless.Coproduct
          |import featherbed._
          |import featherbed.circe._
          |import io.circe.generic.auto._
      """.stripMargin
  )

val validateCommands = List(
  "dependencyUpdates",
  "clean",
  "scalastyle",
  "test:scalastyle",
  "compile",
  "test:compile",
  "coverage",
  "test",
  "docs/tut",
  "coverageReport"
)
addCommandAlias("validate", validateCommands.mkString(";", ";", ""))
