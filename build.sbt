name := "featherbed"

version := "0.1.0"

(scalaVersion in ThisBuild) := "2.11.7"
(resolvers in ThisBuild) += Resolver.sonatypeRepo("snapshots")

libraryDependencies in ThisBuild ++= Seq(
  "com.twitter" %% "finagle-http" % "6.33.0",
  "com.chuusai" %% "shapeless" % "2.3.0",
  "org.typelevel" %% "cats" % "0.4.1",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
)

lazy val `featherbed-core` = project
lazy val `featherbed-circe` = project dependsOn `featherbed-core`

lazy val featherbed = project in file(".") dependsOn (`featherbed-core`, `featherbed-circe`)

tutSettings
tutSourceDirectory := sourceDirectory.value / "tut"
tutTargetDirectory := thisProject.value.base / "doc"