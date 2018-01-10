enablePlugins(GitVersioning, GitBranchPrompt)

name := "featherbed"

lazy val buildSettings = Seq(
  organization := "com.redbubble",
  scalaVersion := "2.12.4"
)

git.useGitDescribe := true

bintrayOrganization := Some("redbubble")

bintrayRepository := "open-source"

bintrayPackageLabels := Seq("scala", "utilities", "util", "circe", "cats", "finagle", "finch")

licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

val finagleVersion = "17.12.0"
val shapelessVersion = "2.3.3"
val catsVersion = "1.0.1"

lazy val docSettings = Seq(
  autoAPIMappings := true
)

lazy val baseSettings = docSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.twitter" %% "finagle-http" % finagleVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % "test",
    "org.scalatest" %% "scalatest" % "3.0.3" % "test"
  ),
  dependencyUpdatesFailBuild := false
)

lazy val allSettings =  baseSettings ++ buildSettings

lazy val `featherbed-core` = project
  .settings(allSettings)

lazy val `featherbed-circe` = project
    .settings(allSettings)
    .dependsOn(`featherbed-core`)

lazy val featherbed = project
  .in(file("."))
  .settings(baseSettings ++ buildSettings)
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
  "coverageReport"
)
addCommandAlias("validate", validateCommands.mkString(";", ";", ""))
