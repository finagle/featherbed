name := "featherbed"

lazy val buildSettings = Seq(
  organization := "com.github.finagle",
  version := "0.1.0",
  scalaVersion := "2.11.8"
)

(resolvers in ThisBuild) += Resolver.sonatypeRepo("snapshots")

val finagleVersion = "6.34.0"
val shapelessVersion = "2.3.0"
val catsVersion = "0.4.1"

libraryDependencies in ThisBuild ++= Seq(
  "com.twitter" %% "finagle-http" % finagleVersion,
  "com.chuusai" %% "shapeless" % shapelessVersion,
  "org.typelevel" %% "cats" % catsVersion,
  "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
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
  homepage := Some(url("https://github.com/finagle/featherbed")),
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

lazy val `featherbed-core` = project
  .settings(publishSettings)

lazy val `featherbed-circe` = project
  .settings(publishSettings)
  .dependsOn(`featherbed-core`)

lazy val featherbed = project
  .in(file("."))
  .settings(noPublish)
  .dependsOn(`featherbed-core`, `featherbed-circe`)

tutSettings
tutSourceDirectory := sourceDirectory.value / "tut"
tutTargetDirectory := thisProject.value.base / "doc"