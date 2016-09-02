name := "featherbed-core"

//featherbed-circe is duplicated in featherbed-core for testing
val circeVersion = "0.5.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % circeVersion % "test",
  "io.circe" %% "circe-generic" % circeVersion % "test",
  "io.circe" %% "circe-parser" % circeVersion % "test"
)
