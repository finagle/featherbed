enablePlugins(GitVersioning, GitBranchPrompt)

name := "featherbed-core"

lazy val buildSettings = Seq(
  organization := "com.redbubble",
  scalaVersion := "2.12.4"
)

git.useGitDescribe := true

bintrayOrganization := Some("redbubble")

bintrayRepository := "open-source"

bintrayPackageLabels := Seq("scala", "utilities", "util", "circe", "cats", "finagle", "finch")

licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

//featherbed-circe is duplicated in featherbed-core for testing
val circeVersion = "0.9.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % circeVersion % "test",
  "io.circe" %% "circe-generic" % circeVersion % "test",
  "io.circe" %% "circe-parser" % circeVersion % "test"
)
