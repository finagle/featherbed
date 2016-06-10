name := "featherbed"

lazy val buildSettings = Seq(
  organization := "io.github.finagle",
  version := "0.2.0-SNAPSHOT",
  scalaVersion := "2.11.8"
)

val finagleVersion = "6.34.0"
val shapelessVersion = "2.3.0"
val catsVersion = "0.6.0"

lazy val baseSettings = Seq(
  libraryDependencies ++= Seq(
    "com.twitter" %% "finagle-http" % finagleVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats" % catsVersion,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test",
    "org.scalatest" %% "scalatest" % "2.2.6" % "test"
  ),
  resolvers += Resolver.sonatypeRepo("snapshots")
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

lazy val allSettings = publishSettings ++ baseSettings ++ buildSettings ++ tutSettings ++ Seq(
  tutSourceDirectory := sourceDirectory.value / "tut",
  tutTargetDirectory := thisProject.value.base / "doc"
)

lazy val `featherbed-core` = project
  .settings(allSettings)

lazy val `featherbed-circe` = project
  .settings(allSettings)
  .dependsOn(`featherbed-core`)

lazy val `docs` = project
  .settings(allSettings)
  .dependsOn(`featherbed-core`, `featherbed-circe`)

lazy val featherbed = project
  .in(file("."))
  .settings(baseSettings ++ buildSettings)
  .aggregate(`featherbed-core`, `featherbed-circe`)
